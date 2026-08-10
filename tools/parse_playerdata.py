# 解析玩家存档:看 hasWarped + 盔甲槽(100-103) + 主背包前几格
import gzip, struct, sys
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

def read_utf8(b, o):
    n = struct.unpack('>H', b[o:o+2])[0]; o += 2
    return b[o:o+n].decode('utf-8', 'replace'), o+n

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
    return read_payload(data, o, rt)[0]

import glob
files = glob.glob('eclipse/saves/新的世界/playerdata/*.dat')
print("playerdata files:", files)
for p in files:
    root = parse(p)
    print(f"\n=== {p} ===")
    props = root.get('HbmPlayerProps')
    if props:
        hw = props[1].get('hasWarped')
        print("HbmPlayerProps.hasWarped =", hw[1] if hw else 'MISSING')
    inv = root.get('Inventory')
    print("Inventory list:", "present" if inv else "MISSING")
    if inv:
        items = inv[1]
        armor_slots = {}
        for it in items:
            slot = it.get('Slot')
            slot_v = slot[1] if slot else None
            if slot_v is not None and 100 <= slot_v <= 103:
                idv = it.get('id')
                dmg = it.get('Damage')
                cnt = it.get('Count')
                tag = 'tag' in it
                armor_slots[slot_v] = (idv[1] if idv else None, dmg[1] if dmg else None, cnt[1] if cnt else None, tag)
        print("盔甲槽 (slot 100-103):", armor_slots if armor_slots else "全部为空/无")
        # 列出主背包非空前 5 个
        nonempty = []
        for it in items:
            slot = it.get('Slot')
            slot_v = slot[1] if slot else None
            idv = it.get('id')
            if slot_v is not None and slot_v < 100 and idv and idv[1] != 0:
                nonempty.append((slot_v, idv[1], it.get('Damage')[1] if it.get('Damage') else 0))
        print("主背包非空格数:", len(nonempty), "前5:", nonempty[:5])
