import re
from collections import defaultdict
from pymongo import MongoClient
MEGA=20; BLOCK_CAP=300
db = MongoClient("mongodb://localhost:27017").scholardex
def norm(s): return re.sub(r"[^a-z\s]"," ",(s or "").lower()).split()
def surname_given(d):
    if not d: return None,[]
    if "," in d:
        sur,_,giv=d.partition(","); return " ".join(norm(sur)), norm(giv)
    t=norm(d)
    return (t[-1], t[:-1]) if t else (None,[])
authors={}
for a in db["scholardex.author_facts"].find({},{"displayName":1,"scopusAuthorIds":1,"orcidIds":1,"openAlexAuthorIds":1}):
    sur,giv=surname_given(a.get("displayName"))
    if not sur: continue
    authors[a["_id"]]=dict(sur=sur,first=(giv[0] if giv else ""),S=bool(a.get("scopusAuthorIds")),
                           O=bool(a.get("orcidIds")),A=bool(a.get("openAlexAuthorIds")),affs=set(),disp=a.get("displayName"))
for e in db["scholardex.author_affiliation_facts"].find({},{"authorId":1,"affiliationId":1}):
    x=authors.get(e["authorId"])
    if x is not None and e.get("affiliationId"): x["affs"].add(e["affiliationId"])
pub_authors=defaultdict(list)
for e in db["scholardex.authorship_facts"].find({},{"publicationId":1,"authorId":1}):
    if e["authorId"] in authors: pub_authors[e["publicationId"]].append(e["authorId"])
pubset=defaultdict(set); coauth=defaultdict(set)
for pid,al in pub_authors.items():
    for x in al: pubset[x].add(pid)
    if len(al)<=MEGA:
        for x in al:
            for y in al:
                if x!=y: coauth[x].add(y)
blocks=defaultdict(list)
for aid,a in authors.items(): blocks[a["sur"]].append(aid)
def name_ok(a,b):
    fa,fb=a["first"],b["first"]
    if not fa or not fb: return True
    if fa==fb: return True
    if len(fa)==1: return fb.startswith(fa)
    if len(fb)==1: return fa.startswith(fb)
    return False
# collect all aff+co>=1 candidate pairs with their shared-co count + block size
pairs=[]; dropped_at2=[]
for sur,ids in blocks.items():
    if len(ids)>BLOCK_CAP: continue
    for i in range(len(ids)):
        for j in range(i+1,len(ids)):
            ia,ib=ids[i],ids[j]; a,b=authors[ia],authors[ib]
            if not name_ok(a,b): continue
            if pubset[ia]&pubset[ib]: continue
            if not (a["affs"]&b["affs"]): continue
            c=len(coauth[ia]&coauth[ib])
            if c>=1:
                pairs.append((ia,ib,c,len(ids)))
                if c==1 and len(dropped_at2)<16: dropped_at2.append((a["disp"],b["disp"],len(ids)))
def mode(members):
    fl=[(authors[m]["S"],authors[m]["A"] or authors[m]["O"]) for m in members]
    if all(s and not oa for s,oa in fl): return "1 Scopus-split"
    if all(oa and not s for s,oa in fl): return "3 OA-internal"
    if any(s for s,_ in fl) and any(oa for _,oa in fl): return "2 cross-source"
    return "mixed"
def run(t):
    parent={}
    def find(x):
        parent.setdefault(x,x)
        while parent[x]!=x: parent[x]=parent[parent[x]]; x=parent[x]
        return x
    n=0
    for ia,ib,c,bs in pairs:
        if c>=t: parent[find(ia)]=find(ib); n+=1
    comp=defaultdict(list)
    for aid in parent: comp[find(aid)].append(aid)
    comps=[m for m in comp.values() if len(m)>1]
    bymode=defaultdict(int)
    for m in comps: bymode[mode(m)]+=len(m)-1
    sizes=defaultdict(int)
    for m in comps: sizes[len(m)]+=1
    return n,len(comps),sum(len(m)-1 for m in comps),bymode,sizes
print(f"authors={len(authors):,}  aff+co>=1 candidate pairs={len(pairs):,}\n")
print(f"{'threshold':10s}{'pairs':>8s}{'merges':>8s}{'absorbed':>10s}   per-mode absorbed                 size-dist")
for t in (1,2,3):
    n,nc,ab,bm,sz=run(t)
    md=" ".join(f"{k}:{v}" for k,v in sorted(bm.items()))
    sd=" ".join(f"{s}:{sz[s]}" for s in sorted(sz))
    print(f"co>={t:<7d}{n:>8,}{nc:>8,}{ab:>10,}   {md:34s}{sd}")
print(f"\nexamples DROPPED when going co>=1 -> co>=2 (co==1, block size):")
for d1,d2,bs in dropped_at2: print(f"  {d1!r:38s} == {d2!r:38s}  block={bs}")
