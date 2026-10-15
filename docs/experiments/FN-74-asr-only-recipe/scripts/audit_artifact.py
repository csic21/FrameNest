#!/usr/bin/env python3
"""Static ELF/AAR acceptance checks. RELRO mismatch is a warning, never a claimed runtime result."""
import argparse,hashlib,io,json,re,struct,subprocess,tempfile,zipfile
from pathlib import Path
from inputs import ROOT,LOCK,digest,license_records
from classfiles import parse,native_bindings

def elf_headers(path):
    b=path.read_bytes()
    if b[:4]!=b'\x7fELF': raise ValueError('Not ELF: '+str(path))
    endian='<' if b[5]==1 else '>'
    machine=struct.unpack_from(endian+'H',b,18)[0]
    if path.parent.name in LOCK['abis']:
        expected={'arm64-v8a':183,'x86_64':62}[path.parent.name]
        if b[4]!=2 or b[5]!=1 or machine!=expected:raise ValueError('ELF class/endianness/machine does not match ABI directory: '+str(path))
    if b[4]==2:
        phoff=struct.unpack_from(endian+'Q',b,32)[0];entsize,num=struct.unpack_from(endian+'HH',b,54)
        layout=endian+'IIQQQQQQ'; is64=True
    elif b[4]==1:
        phoff=struct.unpack_from(endian+'I',b,28)[0];entsize,num=struct.unpack_from(endian+'HH',b,42)
        layout=endian+'IIIIIIII';is64=False
    else: raise ValueError('Unknown ELF class')
    loads=[];relro=[]
    for i in range(num):
        x=struct.unpack_from(layout,b,phoff+i*entsize)
        if is64: typ,flags,offset,vaddr,paddr,filesz,memsz,align=x
        else: typ,offset,vaddr,paddr,filesz,memsz,flags,align=x
        h=dict(offset=offset,vaddr=vaddr,filesz=filesz,memsz=memsz,align=align)
        if typ==1:
            h['load_alignment_16kb_pass']=align>=16384 and (vaddr-offset)%16384==0;loads.append(h)
        if typ==0x6474e552:
            h['unrounded_end']=vaddr+memsz;h['unrounded_end_mod_16384']=(vaddr+memsz)%16384;relro.append(h)
    return loads,relro

def inspect_native(base, readelf):
    expected={f'{abi}/{name}' for abi in LOCK['abis'] for name in LOCK['packaged_native_libraries']}
    actual={str(p.relative_to(base)) for p in base.rglob('*') if p.is_file()}
    if actual!=expected:raise ValueError('Native file whitelist mismatch: '+repr(actual^expected))
    result=[];symbols_by_abi={}
    for name in sorted(expected):
        path=base/name;loads,relro=elf_headers(path)
        dyn=subprocess.check_output([readelf,'--wide','--dynamic',path],text=True)
        symbols=subprocess.check_output([readelf,'--wide','--dyn-syms',path],text=True)
        defined=set()
        for line in symbols.splitlines():
            fields=line.split()
            if len(fields)>=8 and fields[0].endswith(':') and fields[6]!='UND': defined.add(fields[7].split('@')[0])
        if re.search(r'espeak|piper|OfflineTts|OfflineSpeakerDiarization','\n'.join(defined),re.I):raise ValueError('Forbidden export: '+name)
        if not loads or not all(x['load_alignment_16kb_pass'] for x in loads):raise ValueError('PT_LOAD static alignment failed: '+name)
        needed=re.findall(r'\(NEEDED\).*\[([^]]+)\]',dyn)
        permitted={'libonnxruntime.so','libandroid.so','liblog.so','libm.so','libdl.so','libc.so'}
        if set(needed)-permitted:raise ValueError('Unreviewed DT_NEEDED in '+name+': '+repr(needed))
        abi=path.parent.name
        if path.name=='libsherpa-onnx-jni.so':symbols_by_abi[abi]=defined
        warnings=[]
        if not relro:warnings.append('No PT_GNU_RELRO segment; review hardening')
        if any(x['unrounded_end_mod_16384'] for x in relro):warnings.append('Documented RELRO end-alignment static-check warning; no runtime incompatibility demonstrated')
        markers=[s.decode() for s in [b'espeak_ng_',b'piper_phonemize',b'libespeak-ng'] if s in path.read_bytes()]
        if markers:warnings.append('TTS string markers require manual review: '+repr(markers))
        result.append(dict(path=name,sha256=digest(path),size_bytes=path.stat().st_size,needed=needed,load=loads,relro=relro,warnings=warnings))
    return result,symbols_by_abi

def main():
    p=argparse.ArgumentParser(description=__doc__)
    group=p.add_mutually_exclusive_group(required=True);group.add_argument('--native-dir',type=Path);group.add_argument('--aar',type=Path)
    p.add_argument('--readelf',default='readelf');p.add_argument('--report',required=True,type=Path)
    a=p.parse_args();report={'status':'static-checks-only','runtime_tested':False,'documented_relro_rule':'(p_vaddr + p_memsz) % 16384 == 0; no pre-rounding','source':'https://developer.android.com/guide/practices/page-sizes'}
    with tempfile.TemporaryDirectory(prefix='sherpa-asr-audit-') as temp:
        base=a.native_dir;classes=[]
        if a.aar:
            with zipfile.ZipFile(a.aar) as aar:
                names=aar.namelist()
                if len(names)!=len(set(names)):raise ValueError('Duplicate ZIP entry')
                expected={f'jni/{abi}/{lib}' for abi in LOCK['abis'] for lib in LOCK['packaged_native_libraries']}
                actual={n for n in names if n.startswith('jni/') and not n.endswith('/')}
                if actual!=expected:raise ValueError('AAR native inventory mismatch: '+repr(actual^expected))
                base=Path(temp)/'jni'
                for name in expected:
                    target=Path(temp)/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(aar.read(name))
                with zipfile.ZipFile(io.BytesIO(aar.read('classes.jar'))) as jar:
                    if len(jar.namelist())!=len(set(jar.namelist())):raise ValueError('Duplicate classes.jar entry')
                    classes=[parse(jar.read(n)) for n in jar.namelist() if n.endswith('.class')]
                    for n in jar.namelist():
                        if n.endswith('.class') and parse(jar.read(n))['name']+'.class'!=n:raise ValueError('Class path/name mismatch')
                allowed={Path(x['path']).name for x in LOCK['kotlin_sources']}
                for c in classes:
                    if c['name']=='com/k2fsa/sherpa/onnx/BuildConfig':continue
                    if c['source_file'] not in allowed:raise ValueError('Non-whitelisted Kotlin/Java class: '+repr(c['name']))
                    if c['major']!=52:raise ValueError('Unexpected JVM target: '+c['name'])
                required={'com/k2fsa/sherpa/onnx/'+n for n in ['OfflineRecognizer','OfflineStream','OnlineRecognizer','OnlineStream','VersionInfo']}
                if required-{c['name'] for c in classes}:raise ValueError('Required ASR classes missing')
                report['aar_sha256']=digest(a.aar)
                prefix='assets/third_party_licenses/'
                required_notices={str(Path(x['file']).relative_to('licenses')):x['sha256'] for x in license_records()}
                required_notices['NOTICE.txt']=LOCK['notice_template_sha256']
                tools=json.loads(aar.read(prefix+'toolchain.json'))
                required_notices['android-ndk/ndk-runtime-NOTICE.txt']=tools['ndk_runtime_notice_sha256']
                for item in json.loads((ROOT/'toolchain-lock.json').read_text())['ndk_notices']:
                    required_notices['android-ndk/original/'+item['path']]=item['sha256']
                bundle=json.loads(aar.read(prefix+'license-bundle-manifest.json'))
                if len(bundle)!=len({x['path'] for x in bundle}):raise ValueError('Duplicate notice manifest entry')
                if {x['path']:x['sha256'] for x in bundle}!=required_notices:raise ValueError('License bundle inventory mismatch')
                metadata_files={'license-bundle-manifest.json','source-build-manifest.json','toolchain.json','native-output-manifest.json','native-audit.json','strip-evidence.json','imported-native-artifact.json'}
                actual_assets={n.removeprefix(prefix) for n in names if n.startswith(prefix) and not n.endswith('/')}
                if actual_assets!=set(required_notices)|metadata_files:raise ValueError('AAR license/provenance asset fullset mismatch')
                if hashlib.sha256(aar.read(prefix+'source-build-manifest.json')).hexdigest()!=digest(ROOT/'manifest.json'):raise ValueError('AAR source manifest mismatch')
                for name,sha in required_notices.items():
                    if hashlib.sha256(aar.read(prefix+name)).hexdigest()!=sha:raise ValueError('Packaged notice mismatch: '+name)
                report['notice_files_verified']=len(required_notices)
        report['native'],exports=inspect_native(base,a.readelf)
        if classes:
            methods=native_bindings(classes)
            for abi,symbols in exports.items():
                missing=[m for m in methods if not any(s in symbols for s in m['allowed_exports'])]
                if missing:raise ValueError('Missing Kotlin JNI bindings '+abi+': '+repr(missing))
            report['kotlin_classes']=len(classes);report['kotlin_native_methods']=methods
        a.report.parent.mkdir(parents=True,exist_ok=True);a.report.write_text(json.dumps(report,indent=2)+'\n')
        print('Static audit passed; warnings retained and runtime gates OPEN:',a.report)
if __name__=='__main__':main()
