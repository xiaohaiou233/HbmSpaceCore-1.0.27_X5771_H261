import gzip, struct, sys
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
def read_utf8(b,o):
    n=struct.unpack('>H',b[o:o+2])[0];o+=2
    return b[o:o+n].decode('utf-8','replace'),o+n
def read_payload(b,o,t):
    if t==0: return None,o
    if t==1: return struct.unpack('>b',b[o:o+1])[0],o+1
    if t==2: return struct.unpack('>h',b[o:o+2])[0],o+2
    if t==3: return struct.unpack('>i',b[o:o+4])[0],o+4
    if t==4: return struct.unpack('>q',b[o:o+8])[0],o+8
    if t==5: return struct.unpack('>f',b[o:o+4])[0],o+4
    if t==6: return struct.unpack('>d',b[o:o+8])[0],o+8
    if t==7:
        n=struct.unpack('>i',b[o:o+4])[0];o+=4
        return b[o:o+n],o+n
    if t==8:
        s,o=read_utf8(b,o);return s,o
    if t==9:
        et=struct.unpack('>b',b[o:o+1])[0];o+=1
        n=struct.unpack('>i',b[o:o+4])[0];o+=4
        items=[]
        for _ in range(n):
            v,o=read_payload(b,o,et);items.append(v)
        return items,o
    if t==10:
        d={}
        while True:
            tt=struct.unpack('>b',b[o:o+1])[0];o+=1
            if tt==0:break
            name,o=read_utf8(b,o)
            v,o=read_payload(b,o,tt)
            d[name]=(tt,v)
        return d,o
    if t==11:
        n=struct.unpack('>i',b[o:o+4])[0];o+=4
        return list(struct.unpack('>%di'%n,b[o:o+4*n])),o+4*n
    raise ValueError(str(t))
def parse(p):
    raw=open(p,'rb').read();data=gzip.decompress(raw)
    rt=struct.unpack('>b',data[0:1])[0];name,o=read_utf8(data,1)
    return read_payload(data,o,rt)[0]
def show(v,indent=0):
    pad='  '*indent
    t,val=v
    if t==10:
        for k,sub in val.items(): print(f"{pad}{k}: "); show(sub,indent+1)
    elif t==9:
        print(f"{pad}[list x{len(val)}]")
        for it in val: show((t,it),indent+1)
    elif t==8: print(f"{pad}{repr(val)}")
    else: print(f"{pad}{val}")
root=parse('eclipse/config/hbmcore_spawnarmor.dat')
for i,it in enumerate(root['armor'][1]):
    print(f"===== 槽 {i} =====")
    show((10,it))
