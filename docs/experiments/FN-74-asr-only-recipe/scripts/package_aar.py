#!/usr/bin/env python3
"""Stage a minimal official-source Kotlin AAR; strict build requires a separately reviewed Gradle checksum lock."""
import argparse,datetime,hashlib,json,os,re,shutil,stat,subprocess,zipfile
import xml.etree.ElementTree as ET
from pathlib import Path,PurePosixPath
from inputs import ROOT,LOCK,digest,verify,license_records
from build_native import host_provenance

from gradle_lock import validate_gradle_lock

def unique_zip_entries(z):
    names=z.namelist()
    if len(names)!=len(set(names)):raise SystemExit('Duplicate ZIP entry names')
    if any(PurePosixPath(n).is_absolute() or '..' in PurePosixPath(n).parts or '\\' in n or str(PurePosixPath(n))!=n.rstrip('/') for n in names):raise SystemExit('Unsafe ZIP entry path')
    if any(stat.S_ISLNK(info.external_attr>>16) for info in z.infolist()):raise SystemExit('ZIP symlink is forbidden')
    return {n:z.read(n) for n in names if not n.endswith('/')}

def stage(work, project):
    verify()
    info=json.loads((work/'stage.json').read_text());source=Path(info['abis']['arm64-v8a']['source'])
    if project.exists():raise SystemExit('Refusing existing project directory: '+str(project))
    project.mkdir(parents=True)
    for item in LOCK['packaging_wrapper_files']:
        f=item['path'].removeprefix('android/SherpaOnnxAar/')
        if digest(source/item['path'])!=item['sha256']:raise SystemExit('Pinned wrapper checksum mismatch')
        target=project/f;target.parent.mkdir(parents=True,exist_ok=True)
        shutil.copy2(source/'android/SherpaOnnxAar'/f,target)
    os.chmod(project/'gradlew',0o755)
    (project/'gradle/wrapper/gradle-wrapper.properties').write_text('distributionBase=GRADLE_USER_HOME\ndistributionPath=wrapper/dists\ndistributionUrl=https\\://services.gradle.org/distributions/gradle-8.6-bin.zip\ndistributionSha256Sum='+LOCK['toolchain']['gradle_distribution_sha256']+'\nzipStoreBase=GRADLE_USER_HOME\nzipStorePath=wrapper/dists\n')
    (project/'settings.gradle.kts').write_text('''pluginManagement { repositories { google(); mavenCentral() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "SherpaAsrOnly"
include(":asr")
''')
    (project/'build.gradle.kts').write_text('''plugins {
    id("com.android.library") version "8.4.0" apply false
    id("org.jetbrains.kotlin.android") version "1.7.20" apply false
}
''')
    (project/'gradle.properties').write_text('org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8\norg.gradle.daemon=false\nandroid.builder.sdkDownload=false\nkotlin.stdlib.default.dependency=false\n')
    module=project/'asr';module.mkdir()
    (module/'build.gradle.kts').write_text('''plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.k2fsa.sherpa.onnx"
    compileSdk = 34
    buildToolsVersion = "34.0.0"
    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    buildTypes { release { isMinifyEnabled = false } }
    buildFeatures { buildConfig = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
    // Preserve the native bytes verified by the native build manifest.
    packaging { jniLibs { keepDebugSymbols += "**/*.so" } }
}
dependencies { implementation("org.jetbrains.kotlin:kotlin-stdlib:1.7.20") }
tasks.withType<org.gradle.api.tasks.bundling.AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
''')
    # JNI looks up fields/classes by name; don't let host shrinking rename this API.
    (module/'consumer-rules.pro').write_text('-keep class com.k2fsa.sherpa.onnx.** { *; }\n')
    main=module/'src/main';(main/'java/com/k2fsa/sherpa/onnx').mkdir(parents=True)
    (main/'AndroidManifest.xml').write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" />\n')
    for item in LOCK['kotlin_sources']:
        original=source/item['path']
        if digest(original)!=item['sha256']:raise SystemExit('Kotlin source checksum mismatch: '+str(original))
        shutil.copy2(original,main/'java/com/k2fsa/sherpa/onnx'/original.name)
    native=work/'jniLibs'
    if native.exists():shutil.copytree(native,main/'jniLibs')
    assets=main/'assets/third_party_licenses';assets.mkdir(parents=True)
    bundle=[]
    for item in license_records():
        f=ROOT/'dependency-audit'/item['file']
        relative=Path(item['file']).relative_to('licenses')
        target=assets/relative;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(f,target)
        bundle.append({'path':str(relative),'sha256':item['sha256']})
    if (work/'licenses/ndk-runtime-NOTICE.txt').is_file():
        info=json.loads((work/'toolchain.json').read_text())
        original=work/'licenses/ndk-runtime-NOTICE.txt'
        if digest(original)!=info['ndk_runtime_notice_sha256']:raise SystemExit('NDK notice checksum mismatch')
        shutil.copytree(work/'licenses',assets/'android-ndk')
        bundle.append({'path':'android-ndk/ndk-runtime-NOTICE.txt','sha256':digest(original)})
        for item in json.loads((ROOT/'toolchain-lock.json').read_text())['ndk_notices']:
            path=work/'licenses/original'/item['path']
            if not path.is_file() or digest(path)!=item['sha256']:raise SystemExit('Original NDK notice missing/mismatch: '+item['path'])
            bundle.append({'path':'android-ndk/original/'+item['path'],'sha256':item['sha256']})
    shutil.copy2(ROOT/'NOTICE.template',assets/'NOTICE.txt')
    bundle.append({'path':'NOTICE.txt','sha256':LOCK['notice_template_sha256']})
    (assets/'license-bundle-manifest.json').write_text(json.dumps(bundle,indent=2)+'\n')
    shutil.copy2(ROOT/'manifest.json',assets/'source-build-manifest.json')
    for name in ['toolchain.json','native-output-manifest.json','native-audit.json','strip-evidence.json','imported-native-artifact.json']:
        if (work/name).is_file():shutil.copy2(work/name,assets/name)
    metadata={'status':'staged-not-built','native_work':str(work),'wrapper_jar_sha256':digest(project/'gradle/wrapper/gradle-wrapper.jar'),'kotlin_source_count':len(LOCK['kotlin_sources']),'native_present':native.exists(),'manifest_sha256':digest(ROOT/'manifest.json')}
    (project/'recipe-project.json').write_text(json.dumps(metadata,indent=2)+'\n')
    print('STAGED',project)

def build(a,capture=False):
    project=a.project.resolve(); sdk=a.sdk.resolve();meta=json.loads((project/'recipe-project.json').read_text())
    provenance=host_provenance(a.host_image_digest,a.exploratory_host_provenance)
    sdk_license=sdk/'licenses/android-sdk-license'
    if not sdk_license.is_file() or not sdk_license.read_text().strip():raise SystemExit('Pre-existing SDK license record required; no agreement will be accepted')
    if meta['manifest_sha256']!=digest(ROOT/'manifest.json'):raise SystemExit('Manifest changed; restage')
    if digest(project/'gradle/wrapper/gradle-wrapper.jar')!=meta['wrapper_jar_sha256']:raise SystemExit('Wrapper changed')
    for path in [sdk/'platforms/android-34/android.jar',sdk/'build-tools/34.0.0/aapt2']:
        if not path.is_file():raise SystemExit('Required preinstalled SDK artifact missing: '+str(path))
    java=subprocess.run(['java','-version'],capture_output=True,text=True,check=True).stderr
    if not re.search(r'version "21\.',java):raise SystemExit('JDK 21 required')
    (project/'packaging-environment.json').write_text(json.dumps({'host_provenance':provenance,'java_version':java,'java_binary_sha256':digest(Path(shutil.which('java')).resolve()),'android_jar_sha256':digest(sdk/'platforms/android-34/android.jar'),'sdk_aapt2_sha256':digest(sdk/'build-tools/34.0.0/aapt2'),'existing_sdk_license_sha256':digest(sdk_license),'license_acceptance_performed':False},indent=2)+'\n')
    env=os.environ.copy();env.update(ANDROID_HOME=str(sdk),ANDROID_SDK_ROOT=str(sdk),GRADLE_USER_HOME=str(project/'.gradle-home'),SOURCE_DATE_EPOCH=str(LOCK['source_date_epoch']),TZ='UTC',LC_ALL='C.UTF-8')
    args=[str(project/'gradlew'),'--no-daemon',':asr:assembleRelease']
    lockfile=project/'gradle/verification-metadata.xml'
    if capture:
        if lockfile.exists():raise SystemExit('A metadata file already exists; review it rather than silently overwriting')
        args+=['--write-verification-metadata','sha256']
    else:
        if not a.verification_file or not a.verification_sha256:raise SystemExit('Reviewed Gradle lock file and independently recorded SHA-256 required')
        original=a.verification_file.resolve()
        if digest(original)!=a.verification_sha256:raise SystemExit('Reviewed Gradle lock digest mismatch')
        from gradle_lock import validate_gradle_lock,verify_review,read_review
        validate_gradle_lock(original.read_bytes())
        if digest(a.review_file)!=a.review_sha256:raise SystemExit('Gradle independent review digest mismatch')
        verify_review(original.read_bytes(),read_review(a.review_file))
        if not (Path(meta['native_work'])/'strip-evidence.json').is_file():raise SystemExit('Explicit strip evidence required before packaging')
        if original!=lockfile:shutil.copy2(original,lockfile)
        subprocess.run(['python3',str(ROOT/'scripts/audit_artifact.py'),'--native-dir',str(project/'asr/src/main/jniLibs'),'--report',str(project/'prepackage-native-audit.json')],check=True)
        args+=['--dependency-verification','strict']
    with (project/('gradle-lock-capture.log' if capture else 'aar-build.log')).open('w') as log:
        subprocess.run(args,cwd=project,env=env,stdout=log,stderr=subprocess.STDOUT,check=True)
    src=project/'asr/build/outputs/aar/asr-release.aar'
    if capture:
        (project/'LOCK-CAPTURE-QUARANTINE.txt').write_text('NOT A RELEASE ARTIFACT. Review every captured Gradle dependency hash/provenance, commit the approved lock and repeat a strict build. No native/runtime acceptance claimed.\n')
        # Artifact inventory is retained separately from the graph for review; no cache is trusted as authority.
        cache=project/'.gradle-home/caches/modules-2/files-2.1'
        rows=[{'cache_path':str(p.relative_to(cache)),'sha256':digest(p),'size_bytes':p.stat().st_size} for p in sorted(cache.rglob('*')) if p.is_file()]
        (project/'gradle-cache-inventory.json').write_text(json.dumps(rows,indent=2)+'\n')
        print('QUARANTINED lock bootstrap:',lockfile,'sha256',digest(lockfile));return
    out=project/'verified-candidate';out.mkdir(exist_ok=False)
    target=out/'sherpa-onnx-1.13.8-asr-no-tts.aar'
    # Normalize nested classes.jar and AAR entry order/timestamps using deterministic stored ZIP entries.
    # Android packaging alignment is checked on the eventual app APK, not this AAR.
    timestamp=datetime.datetime.fromtimestamp(LOCK['source_date_epoch'],datetime.timezone.utc).timetuple()[:6]
    def zip_data(entries,output):
        with zipfile.ZipFile(output,'w',compression=zipfile.ZIP_STORED) as z:
            for name,data in sorted(entries.items()):
                zi=zipfile.ZipInfo(name,timestamp);zi.create_system=3;zi.external_attr=0o100644<<16;z.writestr(zi,data)
    import io
    with zipfile.ZipFile(src) as z: entries=unique_zip_entries(z)
    with zipfile.ZipFile(io.BytesIO(entries['classes.jar'])) as z: classes=unique_zip_entries(z)
    nested=io.BytesIO();zip_data(classes,nested);entries['classes.jar']=nested.getvalue();zip_data(entries,target)
    subprocess.run(['python3',str(ROOT/'scripts/audit_artifact.py'),'--aar',str(target),'--report',str(out/'static-audit.json')],check=True)
    native_manifest=json.loads((Path(meta['native_work'])/'native-output-manifest.json').read_text())
    with zipfile.ZipFile(target) as z:
        for item in native_manifest['outputs']:
            name='jni/'+item['path'].removeprefix('jniLibs/')
            if hashlib.sha256(z.read(name)).hexdigest()!=item['sha256']:raise SystemExit('AAR changed native bytes: '+name)
    output={'status':'candidate-not-android-accepted','sha256':digest(target),'size_bytes':target.stat().st_size,'gradle_verification_sha256':digest(lockfile),'host_image_digest':a.host_image_digest,'host_provenance':provenance,'reproducibility_proven':False,'release_gate':'closed','gradle_review_sha256':a.review_sha256,'java_version':java,'android_jar_sha256':digest(sdk/'platforms/android-34/android.jar'),'aapt2_sha256':digest(sdk/'build-tools/34.0.0/aapt2'),'source_manifest_sha256':digest(ROOT/'manifest.json')}
    (out/'output-manifest.json').write_text(json.dumps(output,indent=2)+'\n');print('CANDIDATE ONLY',target)

def main():
    p=argparse.ArgumentParser(description=__doc__);s=p.add_subparsers(dest='action',required=True)
    t=s.add_parser('stage');t.add_argument('--work',type=Path,required=True);t.add_argument('--project',type=Path,required=True)
    for name in ['capture-lock','build']:
        t=s.add_parser(name);t.add_argument('--project',type=Path,required=True);t.add_argument('--sdk',type=Path,required=True);host=t.add_mutually_exclusive_group(required=True);host.add_argument('--host-image-digest');host.add_argument('--exploratory-host-provenance',type=Path)
        if name=='build':t.add_argument('--verification-file',type=Path,required=True);t.add_argument('--verification-sha256',required=True);t.add_argument('--review-file',type=Path,required=True);t.add_argument('--review-sha256',required=True)
    a=p.parse_args()
    if a.action=='stage':stage(a.work.resolve(),a.project.resolve())
    else:build(a,capture=a.action=='capture-lock')
if __name__=='__main__':main()
