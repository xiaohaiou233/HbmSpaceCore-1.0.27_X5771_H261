# 解析 landing_capsule.obj 的 o Base / o Capsule 两组顶点范围
import sys

groups = {}
cur = None
with open('src/main/resources/assets/hbm/models/missile_parts/rp_landing_capsule.obj', 'rb') as f:
    for raw in f:
        line = raw.decode('utf-8', 'replace').strip()
        if line.startswith('o '):
            cur = line[2:].strip()
            groups[cur] = []
        elif line.startswith('v ') and cur:
            p = line.split()
            groups[cur].append((float(p[1]), float(p[2]), float(p[3])))

for name, vs in groups.items():
    xs = [v[0] for v in vs]; ys = [v[1] for v in vs]; zs = [v[2] for v in vs]
    print(f"{name}: verts={len(vs)} X[{min(xs):.3f},{max(xs):.3f}] Y[{min(ys):.3f},{max(ys):.3f}] Z[{min(zs):.3f},{max(zs):.3f}]")

# 每个面引用哪些顶点 → 判断 Base/Capsule 是否独立（Base 在下部的推断）
faces = {'Base': 0, 'Capsule': 0, 'unknown': 0}
cur = None
with open('src/main/resources/assets/hbm/models/missile_parts/rp_landing_capsule.obj', 'rb') as f:
    for raw in f:
        line = raw.decode('utf-8', 'replace').strip()
        if line.startswith('o '):
            cur = line[2:].strip()
        elif line.startswith('f ') and cur:
            faces[cur] = faces.get(cur, 0) + 1
print("faces per object:", faces)
