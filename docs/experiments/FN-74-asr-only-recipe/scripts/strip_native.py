#!/usr/bin/env python3
"""Deterministically strip rebuilt JNI only; preserve original debug artifacts and loadable contents."""
import argparse,hashlib,json,shutil,struct,subprocess,tempfile,zipfile
from pathlib import Path
from inputs import ROOT,LOCK,digest
from ci_toolchain import TOOL_LOCK
from audit_artifact import inspect_native

def load_fingerprint(path):
    data=bytearray(path.read_bytes())
    if data[:6]!=b'\x7fELF\x02\x01':raise ValueError('Expected little-endian ELF64')
    phoff=struct.unpack_from('<Q',data,32)[0];size,count=struct.unpack_from('<HH',data,54)
    if size!=56 or phoff+size*count>len(data):raise ValueError('Invalid ELF program headers')
    headers=[struct.unpack_from('<IIQQQQQQ',data,phoff+i*size) for i in range(count)]
    # llvm-strip changes section-table location/count in the ELF header, which is
    # normally in PT_LOAD. These bookkeeping fields are not loaded code or data.
    data[40:48]=b'\0'*8;data[58:64]=b'\0'*6
    loads=[]
    for typ,flags,offset,vaddr,paddr,filesz,memsz,align in headers:
        if typ==1:
            if offset+filesz>len(data):raise ValueError('ELF load outside file')
            loads.append({'offset':offset,'filesz':filesz,'sha256':hashlib.sha256(data[offset:offset+filesz]).hexdigest()})
    if not loads:raise ValueError('No ELF LOAD segment')
    return {'program_headers':headers,'load_bytes_except_section_header_fields':loads}

def strip(work,ndk,provenance):
    if (work/'strip-evidence.json').exists() or (work/'debug-symbols').exists():raise ValueError('Refusing second strip pass')
    tools=json.loads(provenance.read_text())
    if tools.get('toolchain_lock_sha256')!=digest(ROOT/'toolchain-lock.json') or Path(tools['selected']['ndk']).resolve()!=ndk.resolve():raise ValueError('NDK provenance mismatch')
    if digest(ndk/'source.properties')!=TOOL_LOCK['ndk_source_properties_sha256']:raise ValueError('NDK properties mismatch')
    toolbin=ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin';stripbin=toolbin/'llvm-strip';readelf=toolbin/'llvm-readelf'
    archive=ndk.parent.parent/'downloads'/next(x['filename'] for x in TOOL_LOCK['artifacts'] if x['name']=='ndk')
    expected_archive=next(x for x in TOOL_LOCK['artifacts'] if x['name']=='ndk')
    if digest(archive)!=expected_archive['sha256']:raise ValueError('Official NDK archive mismatch')
    with zipfile.ZipFile(archive) as z:
        # The public entrypoints are symlinks to these exact official executables.
        for entry,binary in [('llvm-objcopy',stripbin),('llvm-readobj',readelf)]:
            member=expected_archive['archive_root']+'/toolchains/llvm/prebuilt/linux-x86_64/bin/'+entry
            if hashlib.sha256(z.read(member)).hexdigest()!=digest(binary):raise ValueError('Strip/readelf differs from pinned official archive')
    original_manifest=json.loads((work/'native-output-manifest.json').read_text())
    before_audit,exports_before=inspect_native(work/'jniLibs',str(readelf))
    outputs={row['path']:row for row in original_manifest['outputs']}
    for row in before_audit:
        item=outputs['jniLibs/'+row['path']]
        if item['sha256']!=row['sha256'] or item['size_bytes']!=row['size_bytes']:raise ValueError('Prestrip native digest mismatch')
    debug=work/'debug-symbols';debug.mkdir();evidence=[]
    for abi in LOCK['abis']:
        target=work/'jniLibs'/abi/'libsherpa-onnx-jni.so';original=debug/abi/target.name;original.parent.mkdir();shutil.copy2(target,original)
        fingerprint=load_fingerprint(original)
        command=[str(stripbin),'--strip-unneeded']
        subprocess.run([*command,str(target)],check=True)
        if load_fingerprint(target)!=fingerprint:raise ValueError('Strip changed program headers or loaded code/data')
        with tempfile.TemporaryDirectory() as tmp:
            repeat=Path(tmp)/target.name;shutil.copy2(original,repeat);subprocess.run([*command,str(repeat)],check=True)
            if digest(repeat)!=digest(target):raise ValueError('Repeated stripping produced different bytes')
        evidence.append({'abi':abi,'library':target.name,'command':['llvm-strip','--strip-unneeded'],'original_sha256':digest(original),'original_size_bytes':original.stat().st_size,'stripped_sha256':digest(target),'stripped_size_bytes':target.stat().st_size,'repeat_from_original_identical':True,'load_fingerprint':fingerprint})
    after_audit,exports_after=inspect_native(work/'jniLibs',str(readelf))
    if exports_before!=exports_after:raise ValueError('Strip changed dynamic JNI symbols')
    output=original_manifest.copy();output.update(status='stripped-static-audit-only',strip_tool_sha256=digest(stripbin),release_gate='closed',reproducibility_proven=False)
    output['outputs']=[{'path':'jniLibs/'+row['path'],'sha256':row['sha256'],'size_bytes':row['size_bytes']} for row in after_audit]
    (work/'native-output-manifest.prestrip.json').write_text(json.dumps(original_manifest,indent=2)+'\n')
    (work/'native-output-manifest.json').write_text(json.dumps(output,indent=2)+'\n')
    report={'status':'strip-and-static-checks-only','strip_binary_sha256':digest(stripbin),'strip_version':subprocess.check_output([stripbin,'--version'],text=True),'readelf_sha256':digest(readelf),'ort_policy':'Unchanged pinned vendor bytes; arm64 RELRO warning retained','dynamic_symbols_preserved':True,'libraries':evidence,'native_after':after_audit,'android_runtime_tested':False,'release_gate':'closed'}
    (work/'strip-evidence.json').write_text(json.dumps(report,indent=2)+'\n')
    (debug/'original-manifest.json').write_text(json.dumps(original_manifest,indent=2)+'\n')
    (debug/'strip-evidence.json').write_text(json.dumps(report,indent=2)+'\n')
    subprocess.run(['python3',str(ROOT/'scripts/audit_artifact.py'),'--native-dir',str(work/'jniLibs'),'--readelf',str(readelf),'--report',str(work/'native-audit.json')],check=True)
    print('Stripped JNI deterministically; full original debug artifacts preserved separately:',debug)

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ['work','ndk','toolchain-provenance']:p.add_argument('--'+name,type=Path,required=True)
    a=p.parse_args();strip(a.work,a.ndk,a.toolchain_provenance)
if __name__=='__main__':main()
