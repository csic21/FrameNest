#!/usr/bin/env python3
"""Offline regression checks for exploratory CI provenance and safe tool preparation."""
import io,json,shutil,stat,tempfile,unittest,zipfile
from pathlib import Path
from unittest.mock import patch
import inputs
from inputs import ROOT,LOCK,license_records
from build_native import host_provenance
from ci_toolchain import runner_metadata,safe_zip_extract,TOOL_LOCK,prepare_tools
from collect_ci_artifacts import collect

class NativeCiTests(unittest.TestCase):
    def metadata(self):
        return [{'group':'Runner Image','detail':'Image: ubuntu-24.04\nVersion: 20261004.327.1\nIncluded Software: https://github.com/actions/runner-images/blob/ubuntu24/20261004.327/images/ubuntu/Ubuntu2404-Readme.md\nImage Release: https://github.com/actions/runner-images/releases/tag/ubuntu24%2F20261004.327'}]
    def test_actual_image_links_preserved(self):
        data=runner_metadata(self.metadata(),{'ImageVersion':'20261004.327.1','ImageOS':'ubuntu24'})
        self.assertFalse(data['reproducible']);self.assertIsNone(data['immutable_host_image_digest'])
        self.assertEqual(data['runner_image_release_url'],self.metadata()[0]['detail'].split('Image Release: ')[1])
        self.assertEqual(data['release_gate'],'closed')
    def test_missing_or_inconsistent_image_metadata_fails(self):
        for raw,env in [([],{}),(self.metadata(),{'ImageVersion':'wrong','ImageOS':'ubuntu24'})]:
            with self.assertRaises(ValueError):runner_metadata(raw,env)
    def test_provenance_modes_cannot_fake_reproducibility(self):
        with tempfile.TemporaryDirectory() as temp:
            p=Path(temp)/'host.json';data=runner_metadata(self.metadata(),{'ImageVersion':'20261004.327.1','ImageOS':'ubuntu24'});p.write_text(json.dumps(data))
            self.assertEqual(host_provenance(exploratory_file=p)['kind'],'github-hosted-exploratory')
            with self.assertRaises(ValueError):host_provenance('sha256:'+'a'*64,p)
            data['reproducible']=True;p.write_text(json.dumps(data))
            with self.assertRaises(ValueError):host_provenance(exploratory_file=p)
            with self.assertRaises(ValueError):host_provenance('ubuntu-24.04')
    def test_zip_preserves_internal_tool_symlink_and_mode(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);archive=root/'test.zip'
            with zipfile.ZipFile(archive,'w') as z:
                regular=zipfile.ZipInfo('bin/compiler-19');regular.external_attr=(stat.S_IFREG|0o755)<<16;z.writestr(regular,b'fixture, never executed')
                link=zipfile.ZipInfo('bin/compiler');link.external_attr=(stat.S_IFLNK|0o777)<<16;z.writestr(link,'compiler-19')
            safe_zip_extract(archive,root/'out')
            self.assertTrue((root/'out/bin/compiler').is_symlink());self.assertEqual((root/'out/bin/compiler').read_bytes(),b'fixture, never executed')
            self.assertEqual((root/'out/bin/compiler-19').stat().st_mode&0o777,0o755)
    def test_zip_escape_and_special_file_rejected(self):
        for name,target,mode in [('bin/compiler','../../escape',stat.S_IFLNK|0o777),('../escape','x',stat.S_IFREG|0o644),('device','x',stat.S_IFCHR|0o644)]:
            with self.subTest(name=name),tempfile.TemporaryDirectory() as temp:
                root=Path(temp);archive=root/'test.zip'
                with zipfile.ZipFile(archive,'w') as z:
                    item=zipfile.ZipInfo(name);item.external_attr=mode<<16;z.writestr(item,target)
                with self.assertRaises(ValueError):safe_zip_extract(archive,root/'out')
    def test_no_sdk_agreement_acceptance_fallback(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            with self.assertRaises(SystemExit):prepare_tools(root/'tools',root/'missing-license',root/'report.json',None)
            self.assertFalse((root/'tools').exists())
    def test_missing_and_tampered_notice_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);(root/'dependency-audit').mkdir()
            shutil.copytree(ROOT/'dependency-audit/licenses',root/'dependency-audit/licenses')
            shutil.copy2(ROOT/'dependency-audit/license-file-manifest.json',root/'dependency-audit/license-file-manifest.json')
            shutil.copy2(ROOT/'NOTICE.template',root/'NOTICE.template')
            notice=root/'dependency-audit/licenses/sherpa-onnx/LICENSE';original=notice.read_bytes()
            with patch.object(inputs,'ROOT',root):
                self.assertGreater(len(license_records()),15)
                notice.write_bytes(b'tampered')
                with self.assertRaises(SystemExit):license_records()
                notice.write_bytes(original);notice.unlink()
                with self.assertRaises(SystemExit):license_records()
    def test_tool_versions_and_full_ndk_notices_are_pinned(self):
        versions={x['name']:x['version'] for x in TOOL_LOCK['artifacts']}
        self.assertEqual(versions,{'ndk':LOCK['toolchain']['ndk_revision'],'cmake':LOCK['toolchain']['cmake_version'],'ninja':LOCK['toolchain']['ninja_version']})
        self.assertEqual(len(TOOL_LOCK['ndk_notices']),4)
        self.assertEqual(len(TOOL_LOCK['ndk_combined_notice_sha256']),64)
    def test_failed_build_collector_excludes_large_workspace_inputs(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);work=root/'work';evidence=root/'evidence';tools=root/'tools'
            (work/'arm64-v8a/build').mkdir(parents=True);(work/'arm64-v8a/build/configure.log').write_text('failure fixture')
            (work/'large-model.onnx').write_bytes(b'not for upload');(tools/'downloads').mkdir(parents=True);(tools/'downloads/ndk.zip').write_bytes(b'not for upload')
            collect(work,evidence,tools,root/'out')
            self.assertTrue((root/'out/build-evidence/arm64-v8a/configure.log').is_file())
            files={str(p.relative_to(root/'out')) for p in (root/'out').rglob('*') if p.is_file()}
            self.assertFalse(any('large-model' in p or p.endswith('ndk.zip') for p in files))
            report=json.loads((root/'out/artifact-manifest.json').read_text());self.assertFalse(report['legal_clearance']);self.assertFalse(report['android_runtime_tested'])
if __name__=='__main__':unittest.main()
