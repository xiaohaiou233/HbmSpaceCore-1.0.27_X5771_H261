#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
spark 报告本地解析工具（1.7.10 Forge spark-1.10.19 forge1710 版）

下载 spark.lucko.me/<code> 的元数据(JSON) + 采样数据(protobuf)，本地解析火焰图：
  - 打印每个线程的 Top Inclusive 方法表（火焰图宽度）
  - 用 --path <子串> 打印某个热点的完整调用链（根 → 目标）

用法：
  python spark_parse.py <code>                    # 下载+解析，打印 Top Inclusive
  python spark_parse.py <code> --path FileInputStream.open0
  python spark_parse.py <code> --top 60           # 控制输出条数（默认 45）
  python spark_parse.py <code> --local sampler.bin  # 用本地采样文件，不重新下载

数据源：
  元数据  https://spark-json-service.lucko.me/<code>
  采样    https://spark-usercontent.lucko.me/<code>   (Accept: application/x-spark-sampler)

protobuf 结构（spark_sampler.proto，扁平池 + children_refs 索引）：
  SamplerData { metadata(1); threads(2) }
    ThreadNode { name(1); children(3)=扁平池; times(4)=总样本; children_refs(5)=根索引 }
    StackTraceNode { class_name(3); method_name(4); times(8)=inclusive; children_refs(9)=子索引 }
  关键：node.times 直接就是 inclusive 时间，children_refs 是 packed varint 指向池内索引。

参考：docs/spark-usage.md §10（解析与分析方法）
"""
import struct
import sys
import urllib.request
from collections import defaultdict

# ---------- protobuf 基础 ----------

def read_varint(buf, pos):
    result = 0; shift = 0
    while True:
        b = buf[pos]; pos += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80): break
        shift += 7
    return result, pos

def parse_fields(buf, pos=0, end=None):
    if end is None: end = len(buf)
    fields = []
    while pos < end:
        tag, pos = read_varint(buf, pos)
        field_num = tag >> 3; wire = tag & 7
        if wire == 0:
            val, pos = read_varint(buf, pos); fields.append((field_num, wire, val))
        elif wire == 1:
            val = struct.unpack('<Q', buf[pos:pos+8])[0]; pos += 8; fields.append((field_num, wire, val))
        elif wire == 2:
            ln, pos = read_varint(buf, pos); val = buf[pos:pos+ln]; pos += ln; fields.append((field_num, wire, val))
        elif wire == 5:
            val = struct.unpack('<I', buf[pos:pos+4])[0]; pos += 4; fields.append((field_num, wire, val))
        else:
            raise ValueError('wire %d at %d' % (wire, pos))
    return fields

def unpack_packed_doubles(blob):
    return [struct.unpack('<d', blob[i:i+8])[0] for i in range(0, len(blob), 8)]

def unpack_packed_varints(blob):
    out = []; pos = 0
    while pos < len(blob):
        v, pos = read_varint(blob, pos)
        out.append(v)
    return out

# ---------- 采样数据解析 ----------

def parse_pool_node(buf):
    fs = parse_fields(buf)
    d = {'class_name':'', 'method_name':'', 'times':[], 'children_refs':[]}
    for fn, wt, val in fs:
        if fn == 3: d['class_name'] = val.decode('utf-8','replace')
        elif fn == 4: d['method_name'] = val.decode('utf-8','replace')
        elif fn == 8:
            if wt == 2: d['times'].extend(unpack_packed_doubles(val))
            else: d['times'].append(struct.unpack('<d', struct.pack('<Q', val))[0])
        elif fn == 9:
            if wt == 2: d['children_refs'].extend(unpack_packed_varints(val))
            else: d['children_refs'].append(val)
    return d

def parse_ThreadNode(buf):
    fs = parse_fields(buf)
    d = {'name':'', 'pool':[], 'roots':[], 'times':[]}
    for fn, wt, val in fs:
        if fn == 1: d['name'] = val.decode('utf-8','replace')
        elif fn == 3: d['pool'].append(parse_pool_node(val))
        elif fn == 4:
            if wt == 2: d['times'].extend(unpack_packed_doubles(val))
            else: d['times'].append(struct.unpack('<d', struct.pack('<Q', val))[0])
        elif fn == 5:
            if wt == 2: d['roots'].extend(unpack_packed_varints(val))
            else: d['roots'].append(val)
    return d

def parse_sampler(data):
    """返回 ThreadNode 列表"""
    threads = []
    for fn, wt, val in parse_fields(data):
        if fn == 2:
            threads.append(parse_ThreadNode(val))
    return threads

def lbl(n):
    c = n['class_name']; m = n['method_name']
    return f"{c}.{m}" if c else m

def self_time(n):
    return sum(n['times']) or 0

# ---------- 元数据解析（供 TPS/MSPT 展示） ----------

def fetch_metadata(code):
    url = 'https://spark-json-service.lucko.me/%s' % code
    try:
        with urllib.request.urlopen(url, timeout=30) as r:
            return r.read().decode('utf-8')
    except Exception as e:
        return ''

def summarize_metadata(json_text):
    """尽量打印关键元数据；解析失败就静默跳过（不强依赖 json 模块的结构）"""
    import json
    try:
        md = json.loads(json_text).get('metadata', {})
        ps = md.get('platformStatistics', {})
        tps = ps.get('tps', {})
        mspt = ps.get('mspt', {})
        print('TPS 1m/5m/15m:', tps.get('last1m'), tps.get('last5m'), tps.get('last15m'))
        for key in ('last1m', 'last5m'):
            if key in mspt:
                m = mspt[key]
                print('MSPT %-7s mean=%.2f max=%.2f p95=%.2f' % (
                    key, m.get('mean',0), m.get('max',0), m.get('percentile95',0)))
        td = md.get('threadDumper', {})
        tname = {0:'ALL', 1:'SPECIFIC', 2:'REGEX'}.get(td.get('type'), '?')
        print('threadDumper.type =', tname, '(0=ALL 多线程; 1=SPECIFIC 单线程默认/回落)')
        ss = md.get('systemStatistics', {})
        mem = ss.get('memory', {})
        if mem:
            phys = mem.get('physical', {})
            swap = mem.get('swap', {})
            print('phys mem: %.1f/%.1f GB, swap: %.1f/%.1f GB' % (
                (phys.get('used',0)/1e9), (phys.get('total',0)/1e9),
                (swap.get('used',0)/1e9), (swap.get('total',0)/1e9)))
    except Exception:
        pass

# ---------- 输出 ----------

def print_top_inclusive(threads, top_n):
    for tn in threads:
        thread_total = sum(tn['times']) or 1
        inc = defaultdict(float)
        def walk(idx, seen):
            if idx in seen: return
            seen.add(idx)
            n = tn['pool'][idx]
            inc[lbl(n)] += sum(n['times'])
            for cr in n['children_refs']:
                if 0 <= cr < len(tn['pool']):
                    walk(cr, seen)
        for r in tn['roots']:
            walk(r, set())
        print('\nTHREAD: %s | total samples = %d | pool nodes = %d' % (
            tn['name'], int(thread_total), len(tn['pool'])))
        print('=== TOP INCLUSIVE (top %d, / %d) ===' % (top_n, int(thread_total)))
        for k, v in sorted(inc.items(), key=lambda x:-x[1])[:top_n]:
            print('  %10.1f  %6.2f%%  %s' % (v, v/thread_total*100, k))

def print_path(threads, needle, limit):
    for tn in threads:
        parents = {}
        def build(idx, parent_idx=None):
            if parent_idx is not None:
                parents[idx] = parent_idx
            n = tn['pool'][idx]
            for cr in n['children_refs']:
                if 0 <= cr < len(tn['pool']):
                    build(cr, idx)
        for r in tn['roots']:
            build(r)

        hits = []
        for idx, n in enumerate(tn['pool']):
            if needle in lbl(n):
                hits.append((idx, self_time(n)))
        hits.sort(key=lambda x:-x[1])
        if not hits:
            print('THREAD %s: no node contains %r' % (tn['name'], needle))
            continue
        print('\nTHREAD: %s | %d nodes contain %r, top self=%d' % (
            tn['name'], len(hits), needle, hits[0][1]))
        for idx, st in hits[:limit]:
            path = []
            cur = idx
            while cur is not None:
                path.append(lbl(tn['pool'][cur]))
                cur = parents.get(cur)
            path.reverse()
            print('  [self=%d]' % st)
            for i, p in enumerate(path):
                print('  ' + '  '*min(i, 6) + p)

# ---------- 主流程 ----------

def main():
    args = sys.argv[1:]
    if not args or args[0] in ('-h', '--help', 'help'):
        print(__doc__)
        return

    code = args[0]
    top_n = 45
    needle = None
    path_limit = 3
    local_file = None

    i = 1
    while i < len(args):
        a = args[i]
        if a == '--top' and i+1 < len(args):
            top_n = int(args[i+1]); i += 2
        elif a == '--path' and i+1 < len(args):
            needle = args[i+1]; i += 2
        elif a == '--limit' and i+1 < len(args):
            path_limit = int(args[i+1]); i += 2
        elif a == '--local' and i+1 < len(args):
            local_file = args[i+1]; i += 2
        else:
            print('unknown arg:', a); return

    # 元数据
    md = fetch_metadata(code)
    if md:
        print('==== metadata for %s ====' % code)
        summarize_metadata(md)
    else:
        print('metadata fetch failed (offline?) — continuing with sampler data')

    # 采样数据
    if local_file:
        data = open(local_file, 'rb').read()
    else:
        url = 'https://spark-usercontent.lucko.me/%s' % code
        req = urllib.request.Request(url, headers={'Accept': 'application/x-spark-sampler'})
        with urllib.request.urlopen(req, timeout=60) as r:
            data = r.read()
        print('downloaded sampler: %d bytes' % len(data))

    threads = parse_sampler(data)
    if not threads:
        print('no threads parsed — wrong file?')
        return

    if needle:
        print_path(threads, needle, path_limit)
    else:
        print_top_inclusive(threads, top_n)

if __name__ == '__main__':
    main()
