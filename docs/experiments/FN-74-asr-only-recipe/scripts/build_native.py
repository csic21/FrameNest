#!/usr/bin/env python3
"""Build the isolated, locked no-TTS JNI variant. Does not install tools, run an emulator or publish."""
import argparse,json,os,platform,re,shutil,subprocess
from pathlib import Path
from inputs import ROOT,LOCK,digest,prepare

def run(args, env, log):
    with log.open('w') as f:
        f.write('$ '+' '.join(map(str,args))+'\n'); f.flush()
        subprocess.run(list(map(str,args)),env=env,stdout=f,stderr=subprocess.STDOUT,check=True)

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--work',required=True,type=Path,help='Fresh directory; never reuse a previous build')
    p.add_argument('--ndk',required=True,type=Path,help='Preinstalled official NDK r28c')
    p.add_argument('--cmake',default='cmake');p.add_argument('--ninja',default='ninja')
    p.add_argument('--jobs',type=int,default=4)
    p.add_argument('--ndk-runtime-notices',required=True,type=Path,help='Reviewed runtime notice text from this official NDK; copied and hashed')
    p.add_argument('--host-image-digest',required=True,help='Pinned CI image sha256 digest')
    a=p.parse_args()
    if not re.fullmatch(r'sha256:[0-9a-f]{64}',a.host_image_digest): p.error('Supply real pinned CI image digest')
    if platform.system()!='Linux' or platform.machine()!='x86_64': p.error('This recipe is pinned to Linux x86_64')
    ndk=a.ndk.resolve(); props=(ndk/'source.properties').read_text()
    if not re.search(r'^Pkg.Revision\s*=\s*'+re.escape(LOCK['toolchain']['ndk_revision'])+r'\s*$',props,re.M):
        p.error('NDK Pkg.Revision must equal '+LOCK['toolchain']['ndk_revision'])
    cmake=Path(shutil.which(a.cmake) or a.cmake).resolve();ninja=Path(shutil.which(a.ninja) or a.ninja).resolve()
    cv=subprocess.check_output([cmake,'--version'],text=True).splitlines()[0]
    nv=subprocess.check_output([ninja,'--version'],text=True).strip()
    if cv!='cmake version '+LOCK['toolchain']['cmake_version']: p.error('Wrong CMake version: '+cv)
    if nv!=LOCK['toolchain']['ninja_version']: p.error('Wrong Ninja version: '+nv)
    if not a.ndk_runtime_notices.is_file(): p.error('NDK runtime notice file is required')
    work=a.work.resolve(); stage=prepare(work)
    (work/'licenses').mkdir()
    shutil.copy2(a.ndk_runtime_notices,work/'licenses/ndk-runtime-NOTICE.txt')
    env=os.environ.copy()
    for key in ['CFLAGS','CXXFLAGS','LDFLAGS','CPPFLAGS','CMAKE_PREFIX_PATH','CMAKE_MODULE_PATH','CC','CXX','LD_LIBRARY_PATH']:
        env.pop(key,None)
    env.update(SOURCE_DATE_EPOCH=str(LOCK['source_date_epoch']),TZ='UTC',LC_ALL='C',LANG='C')
    env['SHERPA_ONNXRUNTIME_INCLUDE_DIR']=str(Path(stage['ort'])/'headers')
    toolbin=ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin'
    toolchain={'host_image_digest':a.host_image_digest,'ndk_properties':props,'cmake_version':cv,'ninja_version':nv,
               'cmake_sha256':digest(cmake),'ninja_sha256':digest(ninja),
               'clang_version':subprocess.check_output([toolbin/'clang','--version'],text=True),
               'clang_sha256':digest(toolbin/'clang'),'ndk_runtime_notice_sha256':digest(a.ndk_runtime_notices),'source_manifest_sha256':digest(ROOT/'manifest.json')}
    (work/'toolchain.json').write_text(json.dumps(toolchain,indent=2)+'\n')
    packaged=[]
    for abi,info in stage['abis'].items():
        source=Path(info['source']);build=Path(info['build']);build.mkdir()
        env['SHERPA_ONNXRUNTIME_LIB_DIR']=str(Path(stage['ort'])/'jni'/abi)
        assert (Path(env['SHERPA_ONNXRUNTIME_LIB_DIR'])/'libonnxruntime.so').is_file()
        flags=dict(LOCK['cmake_options']);flags.update(info['dependencies'])
        flags.update(CMAKE_TOOLCHAIN_FILE=str(ndk/'build/cmake/android.toolchain.cmake'),ANDROID_ABI=abi,CMAKE_MAKE_PROGRAM=str(ninja))
        # Map source/build root paths to stable values; no guarantee of byte identity until two clean builds compare.
        flags['CMAKE_C_FLAGS']=flags['CMAKE_CXX_FLAGS']=f'-ffile-prefix-map={work}=/sherpa-build -fdebug-prefix-map={work}=/sherpa-build'
        flags['CMAKE_SHARED_LINKER_FLAGS']=f'-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,-Map={build}/jni-link.map'
        args=[cmake,'-G','Ninja','-S',source,'-B',build,*[f'-D{k}={v}' for k,v in flags.items()]]
        (build/'requested-options.json').write_text(json.dumps(flags,indent=2)+'\n')
        run(args,env,build/'configure.log')
        cache=(build/'CMakeCache.txt').read_text()
        for flag in ['SHERPA_ONNX_ENABLE_TTS','SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION','SHERPA_ONNX_ENABLE_C_API']:
            if not re.search(r'^'+flag+r':BOOL=OFF$',cache,re.M): raise SystemExit('Disabled-feature gate failed: '+flag)
        # Compile-command inspection establishes actual inputs, unlike DT_NEEDED alone for static dependencies.
        commands=json.loads((build/'compile_commands.json').read_text())
        forbidden=[x['file'] for x in commands if re.search(r'(?:espeak|piper|offline-tts|tts-vits)',x['file'],re.I)]
        if forbidden: raise SystemExit('Unexpected TTS compile inputs: '+repr(forbidden))
        run([cmake,'--build',build,'--target',LOCK['native_target'],'--parallel',str(a.jobs)],env,build/'build.log')
        outputs=list(build.rglob('libsherpa-onnx-jni.so'))
        if len(outputs)!=1: raise SystemExit('Expected exactly one JNI output: '+repr(outputs))
        dest=work/'jniLibs'/abi;dest.mkdir(parents=True)
        for src in [outputs[0],Path(env['SHERPA_ONNXRUNTIME_LIB_DIR'])/'libonnxruntime.so']:
            target=dest/src.name;shutil.copy2(src,target)
            packaged.append({'path':str(target.relative_to(work)),'sha256':digest(target),'size_bytes':target.stat().st_size})
        symbols=subprocess.check_output([toolbin/'llvm-nm','--defined-only','--dynamic',outputs[0]],text=True)
        (build/'jni-dynamic-symbols.txt').write_text(symbols)
        if re.search(r'espeak|piper|OfflineTts|OfflineSpeakerDiarization',symbols,re.I): raise SystemExit('Forbidden native exports')
        linkmap=(build/'jni-link.map').read_text()
        if re.search(r'espeak|piper|offline-tts',linkmap,re.I): raise SystemExit('Forbidden native link inputs')
    (work/'native-output-manifest.json').write_text(json.dumps({'status':'built-static-audit-only','outputs':packaged},indent=2)+'\n')
    subprocess.run(['python3',str(ROOT/'scripts/audit_artifact.py'),'--native-dir',str(work/'jniLibs'),'--readelf',str(toolbin/'llvm-readelf'),'--report',str(work/'native-audit.json')],check=True)
    print('Built native candidate. Android runtime/16KB/accuracy/latency gates remain open:',work)
if __name__=='__main__':main()
