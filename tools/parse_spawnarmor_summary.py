# 最小 NBT 解析器:读 SpawnArmorTemplate 存出的 hbmcore_spawnarmor.dat
import gzip, struct, sys
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

def read_utf8(b, o):
    n = struct.unpack('>H', b[o:o+2])[0]; o += 2
    s = b[o:o+n].decode('utf-8', 'replace'); o += n
    return s, o

def read_payload(b, o, t):
    if t == 0: return None, o
    if t == 1: return struct.unpack('>b', b[o:o+1])[0], o+1
    if t == 2: return struct.unpack('>h', b[o:o+2])[0], o+2
    if t == 3: return struct.unpack('>i', b[o:o+4])[0], o+4
    if t == 4: return struct.unpack('>q', b[o:o+8])[0], o+8
    if t == 5: return struct.unpack('>f', b[o:o+4])[0], o+4
    if t == 6: return struct.unpack('>d', b[o:o+8])[0], o+8
    if t == 7:
        n = struct.unpack('>i', b[o:o+4])[0]; o += 4
        return b[o:o+n], o+n
    if t == 8:
        s, o = read_utf8(b, o); return s, o
    if t == 9:
        et = struct.unpack('>b', b[o:o+1])[0]; o += 1
        n = struct.unpack('>i', b[o:o+4])[0]; o += 4
        items = []
        for _ in range(n):
            v, o = read_payload(b, o, et)
            items.append(v)
        return items, o
    if t == 10:
        d = {}
        while True:
            tt = struct.unpack('>b', b[o:o+1])[0]; o += 1
            if tt == 0: break
            name, o = read_utf8(b, o)
            v, o = read_payload(b, o, tt)
            d[name] = (tt, v)
        return d, o
    if t == 11:
        n = struct.unpack('>i', b[o:o+4])[0]; o += 4
        return list(struct.unpack('>%di' % n, b[o:o+4*n])), o+4*n
    raise ValueError('unknown type %d' % t)

def parse(path):
    with open(path, 'rb') as f:
        raw = f.read()
    data = gzip.decompress(raw)
    rt = struct.unpack('>b', data[0:1])[0]
    name, o = read_utf8(data, 1)
    root, _ = read_payload(data, o, rt)
    return root

root = parse('eclipse/config/hbmcore_spawnarmor.dat')
print("=== root keys ===")
for k, (t, v) in root.items():
    print(f"  {k}  (type {t})")

ver = root.get('version')
print("\nversion =", ver[1] if ver else 'MISSING')

armor = root.get('armor')
print("\narmor list type =", armor[0] if armor else 'MISSING')
items = armor[1] if armor else []
print("armor 槽位数 =", len(items))
for i, it in enumerate(items):
    if it is None:
        print(f"  槽 {i}: 空")
    else:
        name = it.get('id')
        dmg = it.get('Damage')
        cnt = it.get('Count')
        # id 在 itemPalette 前是 int id; HBM 存的是 writeToNBT 原始, id 为 item id
        print(f"  槽 {i}: id={name[1] if name else None}, Damage={dmg[1] if dmg else None}, Count={cnt[1] if cnt else None}")
        # 打印顶层 tag 里其它 key
        print(f"        其它 key: {list(it.keys())}")
