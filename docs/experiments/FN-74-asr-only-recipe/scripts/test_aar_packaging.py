#!/usr/bin/env python3
"""Offline checks of explicit stripping and bounded AAR evidence handling."""
import json,struct,tempfile,unittest
from pathlib import Path
from strip_native import load_fingerprint
from collect_aar_evidence import collect
from import_native import import_native
from package_aar import require_fresh_gradle_state,staged_inventory,verify_staged_inventory,audit_gradle_cache
class PackagingTests(unittest.TestCase):
    def elf(self):
        data=bytearray(256);data[:6]=b'\x7fELF\x02\x01';struct.pack_into('<Q',data,32,64);struct.pack_into('<HH',data,54,56,1)
        struct.pack_into('<IIQQQQQQ',data,64,1,5,0,0,0,256,256,16384);return data
    def test_strip_can_change_only_section_bookkeeping_in_load(self):
        with tempfile.TemporaryDirectory() as temp:
            p=Path(temp)/'a';data=self.elf();p.write_bytes(data);before=load_fingerprint(p)
            data[40:48]=b'12345678';data[58:64]=b'123456';p.write_bytes(data);self.assertEqual(before,load_fingerprint(p))
            data[140]=42;p.write_bytes(data);self.assertNotEqual(before,load_fingerprint(p))
    def test_strip_checks_all_program_headers(self):
        with tempfile.TemporaryDirectory() as temp:
            p=Path(temp)/'a';data=self.elf();p.write_bytes(data);before=load_fingerprint(p)
            struct.pack_into('<I',data,68,4);p.write_bytes(data);self.assertNotEqual(before,load_fingerprint(p))
    def test_invalid_load_outside_file_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            p=Path(temp)/'a';data=self.elf();struct.pack_into('<Q',data,96,257);p.write_bytes(data)
            with self.assertRaises(ValueError):load_fingerprint(p)
    def test_unlocked_native_zip_rejected_before_prepare(self):
        with tempfile.TemporaryDirectory() as temp:
            p=Path(temp)/'artifact.zip';p.write_bytes(b'not the reviewed artifact');work=Path(temp)/'work'
            with self.assertRaises(ValueError):import_native(p,work)
            self.assertFalse(work.exists())
    def test_every_downloaded_cache_file_is_verified(self):
        graph=[{'group':'example','name':'lib','version':'1','artifact':'lib-1.jar','sha256':'a'*64}]
        row={'cache_path':'example/lib/1/cachehash/lib-1.jar','sha256':'a'*64,'size_bytes':1}
        self.assertEqual(audit_gradle_cache([row],graph)['actual_files'],1)
        for rows in [[dict(row,sha256='b'*64)],[row,row],[dict(row,cache_path='example/lib/1/cachehash/lib-1.pom')],[]]:
            with self.assertRaises(SystemExit):audit_gradle_cache(rows,graph)
    def test_staged_mutation_and_additions_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);path=root/'settings.gradle.kts';path.write_text('original')
            metadata={'staged_inputs':staged_inventory(root)}
            path.write_text('changed')
            with self.assertRaises(SystemExit):verify_staged_inventory(root,metadata)
            path.write_text('original');(root/'extra.gradle').write_text('new')
            with self.assertRaises(SystemExit):verify_staged_inventory(root,metadata)
    def test_quarantine_or_cached_project_cannot_be_reused(self):
        for name in ['.gradle-home','.gradle','build','asr/build','LOCK-CAPTURE-QUARANTINE.txt']:
            with self.subTest(name=name),tempfile.TemporaryDirectory() as temp:
                root=Path(temp);require_fresh_gradle_state(root)
                path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text('prior run')
                with self.assertRaises(SystemExit):require_fresh_gradle_state(root)
    def test_quarantine_cannot_be_uploaded_as_candidate(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);project=root/'project';raw=project/'asr/build/outputs/aar';raw.mkdir(parents=True);(raw/'asr-release.aar').write_bytes(b'quarantine')
            pending=project/'candidate-quarantine';pending.mkdir();(pending/'sherpa-onnx-1.13.8-asr-no-tts.aar').write_bytes(b'failed final audit')
            cache=project/'.gradle-home/caches';cache.mkdir(parents=True);(cache/'dependency.jar').write_bytes(b'cache')
            (project/'gradle-lock-capture.log').write_text('test log')
            collect(project,root/'work',root/'evidence',root/'out')
            names={str(p.relative_to(root/'out')) for p in (root/'out').rglob('*') if p.is_file()}
            self.assertFalse(any(n.endswith('.aar') or '.gradle-home' in n for n in names))
            self.assertFalse(json.loads((root/'out/artifact-manifest.json').read_text())['aar_built'])
if __name__=='__main__':unittest.main()
