#!/usr/bin/env python3
"""Preparation-only fixtures: no compiled code or Gradle execution."""
import io,json,struct,tempfile,unittest,warnings,zipfile
from pathlib import Path
from inputs import ROOT,LOCK,license_records
from classfiles import native_bindings,jni_escape
from audit_artifact import elf_headers
from package_aar import validate_gradle_lock,unique_zip_entries
class RecipeTests(unittest.TestCase):
    def test_exact_abi_and_native_whitelist(self):
        self.assertEqual(LOCK['abis'],['arm64-v8a','x86_64'])
        self.assertEqual(LOCK['packaged_native_libraries'],['libsherpa-onnx-jni.so','libonnxruntime.so'])
        self.assertEqual(len(LOCK['kotlin_sources']),8)
        self.assertEqual(LOCK['cmake_options']['SHERPA_ONNX_ENABLE_TTS'],'OFF')
        self.assertEqual(LOCK['cmake_options']['SHERPA_ONNX_ENABLE_JNI'],'ON')
    def test_licenses_verified(self):self.assertGreater(len(license_records()),15)
    def test_jni_overload_exact_descriptor(self):
        classes=[{'name':'example/C','methods':[{'access':0x100,'name':'f','descriptor':'(I)V'},{'access':0x100,'name':'f','descriptor':'(J)V'}]}]
        bindings=native_bindings(classes)
        self.assertEqual(bindings[0]['allowed_exports'],['Java_example_C_f__I'])
        self.assertEqual(bindings[1]['allowed_exports'],['Java_example_C_f__J'])
        symbols={'Java_example_C_f__I'}
        self.assertEqual(sum(not any(s in symbols for s in m['allowed_exports']) for m in bindings),1)
        self.assertEqual(jni_escape('VersionInfo$Companion'),'VersionInfo_00024Companion')
    def test_gradle_lock_structural_validation(self):
        valid='<verification-metadata><configuration><verify-metadata>true</verify-metadata><verify-signatures>false</verify-signatures></configuration><components><component group="example" name="lib" version="1"><artifact name="lib-1.jar"><sha256 value="'+'a'*64+'"/></artifact></component></components></verification-metadata>'
        validate_gradle_lock(valid)
        for tag in ['<trusted-artifacts ><trust group=".*" regex="true"/></trusted-artifacts>','<x:trusted-artifacts xmlns:x="urn:test"/>','<ignored-keys/>','<trusted-keys/>']:
            with self.assertRaises(SystemExit):validate_gradle_lock(valid.replace('</configuration>',tag+'</configuration>'))
    def test_duplicate_zip_rejected(self):
        output=io.BytesIO()
        with warnings.catch_warnings():
            warnings.simplefilter('ignore')
            with zipfile.ZipFile(output,'w') as z:z.writestr('a','one');z.writestr('a','two')
        with zipfile.ZipFile(io.BytesIO(output.getvalue())) as z:
            with self.assertRaises(SystemExit):unique_zip_entries(z)
    def test_unsafe_zip_rejected(self):
        output=io.BytesIO()
        with zipfile.ZipFile(output,'w') as z:z.writestr('../a','one')
        with zipfile.ZipFile(io.BytesIO(output.getvalue())) as z:
            with self.assertRaises(SystemExit):unique_zip_entries(z)
    def test_elf_relro_unrounded_and_wrong_abi(self):
        # Synthetic ELF64 program headers only; no binary execution.
        b=bytearray(64+2*56);b[:6]=b'\x7fELF\x02\x01';struct.pack_into('<H',b,18,183)
        struct.pack_into('<Q',b,32,64);struct.pack_into('<HH',b,54,56,2)
        struct.pack_into('<IIQQQQQQ',b,64,1,5,0,0,0,100,100,16384)
        struct.pack_into('<IIQQQQQQ',b,120,0x6474e552,4,0x1000,0x1000,0,0x2000,0x2000,1)
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'arm64-v8a/a.so';path.parent.mkdir();path.write_bytes(b)
            loads,relro=elf_headers(path);self.assertTrue(loads[0]['load_alignment_16kb_pass']);self.assertEqual(relro[0]['unrounded_end_mod_16384'],0x3000)
            wrong=Path(temp)/'x86_64/a.so';wrong.parent.mkdir();wrong.write_bytes(b)
            with self.assertRaises(ValueError):elf_headers(wrong)
if __name__=='__main__':unittest.main()
