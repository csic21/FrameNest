"""Minimal JVM classfile reader for native-method and source-file auditing; no class execution."""
class Reader:
    def __init__(self,b): self.b=b; self.i=0
    def u(self,n):
        v=int.from_bytes(self.b[self.i:self.i+n],'big'); self.i+=n; return v
    def skip(self,n): self.i+=n
    def take(self,n): b=self.b[self.i:self.i+n]; self.i+=n; return b

def parse(b):
    r=Reader(b)
    if r.u(4)!=0xcafebabe: raise ValueError('Not a classfile')
    minor=r.u(2);major=r.u(2);cp=[None]*r.u(2);i=1
    while i<len(cp):
        t=r.u(1)
        if t==1:cp[i]=r.take(r.u(2)).decode('utf8',errors='replace')
        elif t in (3,4):cp[i]=r.u(4)
        elif t in (5,6):r.skip(8);i+=1
        elif t in (7,8,16,19,20):cp[i]=r.u(2)
        elif t in (9,10,11,12,17,18):cp[i]=[r.u(2),r.u(2)]
        elif t==15:r.skip(3)
        else:raise ValueError('Constant-pool tag '+str(t))
        i+=1
    access=r.u(2);name=cp[cp[r.u(2)]];r.u(2)
    for _ in range(r.u(2)):r.u(2)
    def attrs():
        a={}
        for _ in range(r.u(2)):
            k=cp[r.u(2)];a[k]=r.take(r.u(4))
        return a
    def members():
        a=[]
        for _ in range(r.u(2)):
            acc=r.u(2);n=cp[r.u(2)];d=cp[r.u(2)];attrs();a.append(dict(access=acc,name=n,descriptor=d))
        return a
    fields=members();methods=members();attributes=attrs()
    source=cp[int.from_bytes(attributes['SourceFile'],'big')] if 'SourceFile' in attributes else None
    return dict(name=name,major=major,minor=minor,source_file=source,fields=fields,methods=methods)

def jni_escape(s):
    out=''
    for c in s:
        if c.isascii() and c.isalnum():out+=c
        elif c=='/':out+='_' 
        elif c=='_':out+='_1'
        elif c==';':out+='_2'
        elif c=='[':out+='_3'
        else:
            raw=c.encode('utf-16-be')
            out+=''.join('_0'+raw[i:i+2].hex() for i in range(0,len(raw),2))
    return out

def native_bindings(classes):
    result=[]
    for c in classes:
        methods=[m for m in c['methods'] if m['access']&0x100]
        for m in methods:
            short='Java_'+jni_escape(c['name'])+'_'+jni_escape(m['name'])
            params=m['descriptor'].split(')',1)[0][1:]
            long=short+'__'+jni_escape(params)
            overloaded=sum(x['name']==m['name'] for x in methods)>1
            result.append(dict(class_name=c['name'],method=m['name'],descriptor=m['descriptor'],allowed_exports=[long] if overloaded else [short,long]))
    return result
