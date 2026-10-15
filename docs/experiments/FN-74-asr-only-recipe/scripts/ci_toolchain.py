#!/usr/bin/env python3
"""Prepare isolated, checksum-verified official native tools; never accept SDK agreements."""
import argparse,hashlib,json,os,platform,posixpath,shutil,stat,subprocess,tarfile,urllib.request,zipfile
from pathlib import Path, PurePosixPath
from inputs import ROOT,digest
TOOL_LOCK=json.loads((ROOT/'toolchain-lock.json').read_text())

def runner_metadata(raw,env):
    """Use the installed image's actual release links; do not synthesize an OCI digest."""
    if not isinstance(raw,list):raise ValueError('Unexpected installed runner image metadata schema')
    groups=[x for x in raw if isinstance(x,dict) and x.get('group')=='Runner Image']
    if len(groups)!=1 or not isinstance(groups[0].get('detail'),str):raise ValueError('Runner Image metadata unavailable')
    fields=dict(line.split(': ',1) for line in groups[0]['detail'].splitlines() if ': ' in line)
    version=fields.get('Version','');release=fields.get('Image Release','');software=fields.get('Included Software','')
    if not version or version!=env.get('ImageVersion'):raise ValueError('Installed and environment image versions disagree')
    if fields.get('Image')!='ubuntu-24.04' or env.get('ImageOS')!='ubuntu24':raise ValueError('Expected Ubuntu 24.04 hosted image')
    if not release.startswith('https://github.com/actions/runner-images/releases/tag/'):raise ValueError('Actual official release URL missing')
    if not software.startswith('https://github.com/actions/runner-images/blob/'):raise ValueError('Actual official software manifest URL missing')
    return {'kind':'github-hosted-exploratory','reproducible':False,'release_gate':'closed',
            'immutable_host_image_digest':None,'runner_image_os':env['ImageOS'],'runner_image_version':version,
            'runner_image_release_url':release,'runner_image_manifest_url':software,'installed_image_metadata':raw}

def record_host(out,metadata):
    if os.environ.get('GITHUB_ACTIONS')!='true':raise SystemExit('Exploratory hosted provenance is only captured inside GitHub Actions')
    if not metadata.is_file():raise SystemExit('Installed /imagegeneration/imagedata.json missing; do not invent release metadata')
    result=runner_metadata(json.loads(metadata.read_text()),os.environ)
    result['github']={k:os.environ.get(k) for k in ['GITHUB_REPOSITORY','GITHUB_SHA','GITHUB_RUN_ID','GITHUB_RUN_ATTEMPT','GITHUB_WORKFLOW']}
    result['platform']=platform.platform();result['python_version']=platform.python_version()
    result['installed_tools']={}
    for name in ['cmake','ninja']:
        path=shutil.which(name)
        result['installed_tools'][name]={'path':path}
        if path:result['installed_tools'][name]['version_output']=subprocess.check_output([path,'--version'],text=True).strip()
    sdk=Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or '/nonexistent')
    result['installed_ndk_revisions']=[{'directory':p.parent.name,'source_properties':p.read_text()} for p in sorted((sdk/'ndk').glob('*/source.properties'))]
    out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(result,indent=2)+'\n');print('Recorded exploratory hosted image provenance:',out)

def fetch(item,cache):
    cache.mkdir(parents=True,exist_ok=True);path=cache/item['filename']
    if not path.exists():
        partial=path.with_name(path.name+'.partial')
        if partial.exists():raise SystemExit('Inspect partial download before retry: '+str(partial))
        request=urllib.request.Request(item['url'],headers={'User-Agent':'FrameNest-native-recipe/1'})
        print('Downloading official pinned tool:',item['name'],flush=True)
        with urllib.request.urlopen(request,timeout=180) as source,partial.open('xb') as target:shutil.copyfileobj(source,target)
        if partial.stat().st_size!=item['size_bytes'] or digest(partial)!=item['sha256']:raise SystemExit('Tool archive mismatch; partial file retained')
        partial.rename(path)
    if path.stat().st_size!=item['size_bytes'] or digest(path)!=item['sha256']:raise SystemExit('Tool archive checksum/size mismatch: '+str(path))
    return path

def safe_zip_extract(archive,target):
    """Preserve NDK's relative clang symlinks and executable modes, rejecting escapes."""
    target.mkdir(parents=True,exist_ok=False);links=[]
    with zipfile.ZipFile(archive) as z:
        names=z.namelist()
        if len(names)!=len(set(names)):raise ValueError('Duplicate tool ZIP entry')
        for info in z.infolist():
            name=PurePosixPath(info.filename)
            if name.is_absolute() or '..' in name.parts or '\\' in info.filename:raise ValueError('Unsafe tool ZIP path')
            out=target.joinpath(*name.parts);mode=info.external_attr>>16
            if info.is_dir():out.mkdir(parents=True,exist_ok=True);continue
            out.parent.mkdir(parents=True,exist_ok=True)
            if stat.S_ISLNK(mode):
                link=z.read(info).decode('utf-8')
                resolved=PurePosixPath(posixpath.normpath(str(name.parent/link)))
                if PurePosixPath(link).is_absolute() or resolved.is_absolute() or '..' in resolved.parts:raise ValueError('Escaping ZIP symlink')
                links.append((out,link));continue
            if stat.S_IFMT(mode) not in (0,stat.S_IFREG):raise ValueError('Special tool ZIP entry')
            with z.open(info) as src,out.open('xb') as dst:shutil.copyfileobj(src,dst)
            if mode&0o777:out.chmod(mode&0o777)  # Never copy setuid/setgid bits.
        for out,link in links:
            out.symlink_to(link)
        for out,_ in links:
            if not out.resolve().is_relative_to(target.resolve()):raise ValueError('Escaping ZIP symlink chain')

def prepare_tools(root,sdk_license,report,github_output):
    if platform.system()!='Linux' or platform.machine()!='x86_64':raise SystemExit('Linux x86_64 required')
    # This is a precondition only, not acceptance or a claim about legal clearance.
    if not sdk_license.is_file() or not sdk_license.read_text().strip():raise SystemExit('Pre-existing Android SDK license record missing; no agreement will be accepted automatically')
    if root.exists():raise SystemExit('Refusing to reuse tool installation directory: '+str(root))
    root.mkdir(parents=True);selected={};archives=[]
    for item in TOOL_LOCK['artifacts']:
        archive=fetch(item,root/'downloads');target=root/item['name']
        if archive.suffix=='.zip':safe_zip_extract(archive,target)
        else:
            target.mkdir()
            with tarfile.open(archive) as tar:tar.extractall(target,filter='data')
        install=target/item['archive_root'] if item['archive_root'] else target
        selected[item['name']]=str(install)
        if item.get('binary_path') and digest(install/item['binary_path'])!=item['binary_sha256']:raise SystemExit('Tool executable checksum mismatch')
        archives.append({'name':item['name'],'url':item['url'],'sha256':digest(archive),'size_bytes':archive.stat().st_size})
    ndk=Path(selected['ndk']);props=ndk/'source.properties'
    if digest(props)!=TOOL_LOCK['ndk_source_properties_sha256']:raise SystemExit('NDK properties mismatch')
    notices=root/'notices';notices.mkdir();records=[];combined=bytearray()
    for item in TOOL_LOCK['ndk_notices']:
        original=ndk/item['path']
        if not original.is_file() or original.stat().st_size!=item['size_bytes'] or digest(original)!=item['sha256']:raise SystemExit('NDK aggregate notice mismatch: '+item['path'])
        copy=notices/item['path'];copy.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(original,copy)
        combined.extend(('===== Official Android NDK r28c: '+item['path']+' =====\n').encode());combined.extend(original.read_bytes());combined.extend(b'\n')
        records.append(item)
    combined_path=root/'ndk-runtime-NOTICE.txt';combined_path.write_bytes(combined)
    if digest(combined_path)!=TOOL_LOCK['ndk_combined_notice_sha256']:raise SystemExit('Combined NDK notice differs from pinned bytes')
    result={'status':'official-toolchains-prepared','toolchain_lock_sha256':digest(ROOT/'toolchain-lock.json'),'archives':archives,'selected':selected,
            'existing_sdk_license_record_sha256':digest(sdk_license),'license_acceptance_performed':False,
            'ndk_notices':records,'ndk_runtime_notices':str(combined_path),'ndk_runtime_notice_sha256':digest(combined_path)}
    report.parent.mkdir(parents=True,exist_ok=True);report.write_text(json.dumps(result,indent=2)+'\n')
    if github_output:
        with github_output.open('a') as f:
            for k,v in {'ndk':selected['ndk'],'cmake':str(Path(selected['cmake'])/'bin/cmake'),'ninja':str(Path(selected['ninja'])/'ninja'),'ndk_notices':str(combined_path)}.items():
                if '\n' in v or '\r' in v:raise ValueError('Unsafe workflow output path')
                f.write(k+'='+v+'\n')
    print('Prepared official pinned native tools; no SDK licenses accepted:',report)

def main():
    p=argparse.ArgumentParser(description=__doc__);s=p.add_subparsers(dest='action',required=True)
    h=s.add_parser('record-host');h.add_argument('--report',type=Path,required=True);h.add_argument('--image-metadata',type=Path,default=Path('/imagegeneration/imagedata.json'))
    t=s.add_parser('prepare');t.add_argument('--root',type=Path,required=True);t.add_argument('--sdk-license',type=Path,required=True);t.add_argument('--report',type=Path,required=True);t.add_argument('--github-output',type=Path)
    a=p.parse_args()
    if a.action=='record-host':record_host(a.report,a.image_metadata)
    else:prepare_tools(a.root.resolve(),a.sdk_license.resolve(),a.report.resolve(),a.github_output)
if __name__=='__main__':main()
