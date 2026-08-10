# 按 方块类型+meta 对比真实差异 + 端口 meta 检查
import nbtlib, sys
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

def load(p): return nbtlib.load(p)
new = load("eclipse/structures/spawn_station.nbt")
cur = load("src/main/resources/assets/hbm/structures/spawn_station.nbt")

def pal_full(d, idx):
    p = d['palette'][idx]
    name = str(p['Name'])
    meta = ''
    if 'Properties' in p and 'meta' in p['Properties']:
        meta = str(p['Properties']['meta'])
    return f"{name}#{meta}"

newset = {tuple(int(v) for v in b['pos']): pal_full(new, int(b['state'])) for b in new['blocks']}
curset = {tuple(int(v) for v in b['pos']): pal_full(cur, int(b['state'])) for b in cur['blocks']}

same = newset.keys() & curset.keys()
real_diff = {p: (curset[p], newset[p]) for p in same if curset[p] != newset[p]}
only_new = {p: newset[p] for p in newset.keys()-curset.keys()}
only_cur = {p: curset[p] for p in curset.keys()-newset.keys()}

print("=== 按 类型+meta 的真实差异 ===")
print(f"改变={len(real_diff)} 新增={len(only_new)} 移除={len(only_cur)}")
for p,(c,n) in list(real_diff.items())[:30]:
    print(f"  改 @{p}: {c} -> {n}")
for p,n in only_new.items():
    print(f"  增 @{p}: {n}")
for p,c in only_cur.items():
    print(f"  删 @{p}: {c}")

# 端口 meta 检查（NEW 里所有 orbital_station_port）
print("\n=== NEW 里所有 orbital_station_port 的 meta ===")
metas = set()
for b in new['blocks']:
    f = pal_full(new, int(b['state']))
    if 'orbital_station_port' in f:
        metas.add(f)
print("  metas:", sorted(metas))

# 金块检查
print("\n=== 金块检查 ===")
for b in new['blocks']:
    f = pal_full(new, int(b['state']))
    if 'gold' in f.lower():
        print("  发现金块 @", tuple(int(v) for v in b['pos']), f)
print("  (无输出=无金块)")
