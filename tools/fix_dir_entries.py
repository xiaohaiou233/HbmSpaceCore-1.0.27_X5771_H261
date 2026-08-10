#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
fix_dir_entries.py — 给 reobf 后的 jar 补回 ZIP 目录条目

背景（见 docs/coremod-obfuscation-fix.md + 记忆 mod-reobf-mcp-srg）：
1.7.10 ForgeGradle reobf 会用 SpecialSource 重写 jar，产物会剥离所有目录条目
（`com/hbm/`、`com/hbm/core/` 等目录项丢失，只剩文件条目）。
某些 mod（如 OpenComputers）用 `clazz.getResource("assets/xxx/dir/")` 探测目录是否存在，
目录条目丢失会导致资源找不到（如 HBM 的 OC 磁盘 PWRangler 加载失败 → NPE）。

用法：
    python fix_dir_entries.py in.jar out.jar
    # 或原地修复：python fix_dir_entries.py --inplace jar1.jar jar2.jar ...

依赖：Python 3（标准库 zipfile，无第三方依赖）。
"""
import sys
import zipfile
import shutil
import os


def collect_dirs(zf):
    """从现有条目推断出所有应存在的目录路径（含前缀斜杠）。"""
    dirs = set()
    for name in zf.namelist():
        if name.endswith('/'):
            dirs.add(name)
        # 文件条目 -> 补齐其各级父目录
        parts = name.split('/')
        for i in range(1, len(parts)):
            dirs.add('/'.join(parts[:i]) + '/')
    return dirs


def fix(in_path, out_path):
    if os.path.abspath(in_path) == os.path.abspath(out_path):
        raise ValueError("in and out must differ; use --inplace to modify a jar in place")

    with zipfile.ZipFile(in_path, 'r') as zin:
        infos = zin.infolist()
        # 保留原目录条目 + 推断缺失的目录条目，按路径排序稳定输出
        dirs = collect_dirs(zin)
        # 排除已有显式目录条目
        existing = {i.filename for i in infos}
        missing = sorted(d for d in dirs if d not in existing)

        with zipfile.ZipFile(out_path, 'w', zipfile.ZIP_DEFLATED) as zout:
            # 先写目录条目（补回被剥离的）
            for d in missing:
                zi = zipfile.ZipInfo(d, date_time=(1980, 1, 1, 0, 0, 0))
                zi.external_attr = 0o040755 << 16  # dir
                zout.writestr(zi, b'')
            # 再写所有文件
            for i in infos:
                zout.writestr(i, zin.read(i.filename))

        print(f"OK: {in_path} -> {out_path}  (added {len(missing)} dir entries)")


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        sys.exit(1)

    if args[0] == '--inplace':
        for p in args[1:]:
            tmp = p + '.tmp'
            fix(p, tmp)
            shutil.move(tmp, p)
        print("done: all jars fixed in place")
    elif len(args) == 2:
        fix(args[0], args[1])
    else:
        print(__doc__)
        sys.exit(1)


if __name__ == '__main__':
    main()
