"""Offline structural fixtures; these tests do not claim Android runtime coverage."""
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile
import zlib

SPEC = importlib.util.spec_from_file_location("check_source_asr_apk", Path(__file__).resolve().parents[1] / "check_source_asr_apk.py")
c = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(c)
ASR = "Lcom/k2fsa/sherpa/onnx/OfflineRecognizer;"
TTS = "Lcom/k2fsa/sherpa/onnx/OfflineTts;"
ORT = "Lai/onnxruntime/OrtEnvironment;"
RESOURCE = "Lcom/k2fsa/sherpa/onnx/R;"
OBJECT = "Ljava/lang/Object;"


def uleb(value):
    result = bytearray()
    while value >= 128:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return result


def seal_dex(data):
    data = bytearray(data)
    data[12:32] = hashlib.sha1(data[32:]).digest()
    struct.pack_into("<I", data, 8, zlib.adler32(data[12:]) & 0xffffffff)
    return bytes(data)


def make_dex(classes=(ASR,), references=(), strings=()):
    """Minimal DEX with real ID tables, map and class_defs; no executable code."""
    all_types = sorted(set(classes) | set(references))
    all_strings = sorted(set(all_types) | set(strings))
    strings_off = 112 if all_strings else 0
    types_off = 112 + len(all_strings) * 4 if all_types else 0
    classes_off = 112 + len(all_strings) * 4 + len(all_types) * 4 if classes else 0
    data_off = 112 + len(all_strings) * 4 + len(all_types) * 4 + len(classes) * 32
    data = bytearray(data_off)
    data[:8] = b"dex\n035\0"
    string_offsets = []
    for value in all_strings:
        string_offsets.append(len(data))
        data.extend(uleb(len(value)))
        data.extend(value.encode("utf-8"))
        data.append(0)
    data.extend(b"\0" * (-len(data) % 4))
    map_off = len(data)
    items = [(0, 1, 0)]
    if all_strings:
        items.append((1, len(all_strings), strings_off))
    if all_types:
        items.append((2, len(all_types), types_off))
    if classes:
        items.append((6, len(classes), classes_off))
    if all_strings:
        items.append((0x2002, len(all_strings), data_off))
    items.append((0x1000, 1, map_off))
    data.extend(struct.pack("<I", len(items)))
    for kind, count, offset in items:
        data.extend(struct.pack("<HHII", kind, 0, count, offset))
    struct.pack_into("<20I", data, 32, len(data), 112, 0x12345678, 0, 0, map_off,
                     len(all_strings), strings_off, len(all_types), types_off,
                     0, 0, 0, 0, 0, 0, len(classes), classes_off,
                     len(data) - data_off, data_off)
    for i, offset in enumerate(string_offsets):
        struct.pack_into("<I", data, strings_off + i * 4, offset)
    for i, value in enumerate(all_types):
        struct.pack_into("<I", data, types_off + i * 4, all_strings.index(value))
    for i, value in enumerate(classes):
        struct.pack_into("<8I", data, classes_off + i * 32, all_types.index(value), 1,
                         0xffffffff, 0, 0xffffffff, 0, 0, 0)
    return seal_dex(data)


def make_resource_dex(constructor=False, method_name="<init>", method_flags=0x10001,
                      field_count=0, interface=False, superclass=OBJECT,
                      instructions=None, code_override=None, direct_count=None,
                      virtual_count=0, class_flags=0x11):
    """AGP-like empty R fixture with optional narrowly executable constructor."""
    classes = [ASR, RESOURCE]
    types = sorted({ASR, RESOURCE, OBJECT, "V", superclass})
    strings = sorted(set(types) | {"<init>", method_name, "field"})
    string_off = 112
    type_off = string_off + len(strings) * 4
    proto_off = type_off + len(types) * 4
    method_off = proto_off + 12
    class_off = method_off + 16
    data_off = class_off + len(classes) * 32
    data = bytearray(data_off)
    data[:8] = b"dex\n035\0"
    offsets = []
    for value in strings:
        offsets.append(len(data))
        data.extend(uleb(len(value)) + value.encode() + b"\0")
    data.extend(b"\0" * (-len(data) % 4))
    items = [(0, 1, 0), (1, len(strings), string_off), (2, len(types), type_off),
             (3, 1, proto_off), (5, 2, method_off), (6, 2, class_off),
             (0x2002, len(strings), data_off)]
    interface_off = 0
    if interface:
        interface_off = len(data)
        data.extend(struct.pack("<IH", 1, types.index(OBJECT)))
        items.append((0x1001, 1, interface_off))
        data.extend(b"\0" * (-len(data) % 4))
    code_off = len(data)
    data.extend(struct.pack("<4H2I", 1, 1, 1, 0, 0, 4))
    data.extend(struct.pack("<4H", *(instructions or (0x1070, 1, 0, 0x000e))))
    items.append((0x2001, 1, code_off))
    class_data = len(data)
    direct = int(constructor) if direct_count is None else direct_count
    data.extend(uleb(field_count) + b"\0" + uleb(direct) + uleb(virtual_count))
    if constructor:
        data.extend(uleb(0) + uleb(method_flags) + uleb(code_off if code_override is None else code_override))
    items.append((0x2000, 1, class_data))
    data.extend(b"\0" * (-len(data) % 4))
    map_off = len(data)
    items.append((0x1000, 1, map_off))
    data.extend(struct.pack("<I", len(items)))
    for kind, count, offset in items:
        data.extend(struct.pack("<HHII", kind, 0, count, offset))
    struct.pack_into("<20I", data, 32, len(data), 112, 0x12345678, 0, 0, map_off,
                     len(strings), string_off, len(types), type_off, 1, proto_off,
                     0, 0, 2, method_off, 2, class_off, len(data) - data_off, data_off)
    for i, offset in enumerate(offsets):
        struct.pack_into("<I", data, string_off + i * 4, offset)
    for i, name in enumerate(types):
        struct.pack_into("<I", data, type_off + i * 4, strings.index(name))
    struct.pack_into("<III", data, proto_off, strings.index("V"), types.index("V"), 0)
    struct.pack_into("<HHI", data, method_off, types.index(RESOURCE), 0, strings.index(method_name))
    struct.pack_into("<HHI", data, method_off + 8, types.index(OBJECT), 0, strings.index("<init>"))
    for i, name in enumerate(classes):
        struct.pack_into("<8I", data, class_off + i * 32, types.index(name), class_flags if name == RESOURCE else 1,
                         types.index(superclass if name == RESOURCE else OBJECT),
                         interface_off if name == RESOURCE else 0, 0xffffffff, 0,
                         class_data if name == RESOURCE else 0, 0)
    return seal_dex(data)


def mutate(data, offset, value):
    changed = bytearray(data)
    struct.pack_into("<I", changed, offset, value)
    return seal_dex(changed)


def make_elf(abi="arm64-v8a", align=16384, relro_remainder=0):
    data = bytearray(176)
    data[:7] = b"\x7fELF\x02\x01\x01"
    struct.pack_into("<HHIQQQIHHHHHH", data, 16, 3, c.ABIS[abi], 1, 0, 64, 0, 0,
                     64, 56, 2, 0, 0, 0)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, len(data), len(data), align)
    struct.pack_into("<IIQQQQQQ", data, 120, 0x6474e552, 4, 0, relro_remainder,
                     0, 0, 0, 1)
    return bytes(data)


def reference():
    native = {f"{abi}/{lib}": make_elf(abi) for abi in c.ABIS for lib in c.LIBRARIES}
    return {"native": native, "native_info": {n: c.elf_report(v, n.split('/')[0])[0] for n, v in native.items()},
            "assets": {c.NOTICE_PREFIX + "NOTICE.txt": b"fixture notice",
                       c.NOTICE_PREFIX + "attribution.txt": b"fixture attribution"},
            "classes": {ASR}, "native_declaration_count": 1, "notice_count": 2}


def apk_members(ref, abis=("arm64-v8a", "x86_64")):
    return {**{f"lib/{n}": v for n, v in ref["native"].items() if n.split('/')[0] in abis},
            **ref["assets"], "classes.dex": make_dex(sorted(ref["classes"])),
            "AndroidManifest.xml": b"not an installable APK; structural fixture only"}


def write_zip(path, members, compression=zipfile.ZIP_STORED, align=False):
    with zipfile.ZipFile(path, "w", compression=compression) as archive:
        for name, value in members.items():
            if align and name.endswith(".so"):
                info = zipfile.ZipInfo(name)
                start = archive.fp.tell() + 30 + len(name.encode())
                padding = (-start) % c.PAGE
                if 0 < padding < 4:
                    padding += c.PAGE
                if padding:
                    info.extra = struct.pack("<HH", 0xCAFE, padding - 4) + bytes(padding - 4)
                archive.writestr(info, value)
            else:
                archive.writestr(name, value)


class DexTests(unittest.TestCase):
    def test_only_class_definitions_count(self):
        self.assertEqual(c.dex_classes(make_dex([ASR], [TTS, ORT], ["GeneratedAudio"])), {ASR})

    def test_actual_forbidden_class_definition_is_visible(self):
        self.assertEqual(c.dex_classes(make_dex([ASR, TTS])), {ASR, TTS})

    def test_duplicates_rejected(self):
        with self.assertRaisesRegex(ValueError, "Duplicate DEX class"):
            c.dex_classes(make_dex([ASR, ASR]))

    def test_checksum_and_signature_rejected(self):
        good = make_dex()
        damaged = bytearray(good)
        damaged[-1] ^= 1
        with self.assertRaisesRegex(ValueError, "checksum"):
            c.dex_classes(damaged)
        damaged = bytearray(good)
        damaged[12] ^= 1
        struct.pack_into("<I", damaged, 8, zlib.adler32(damaged[12:]) & 0xffffffff)
        with self.assertRaisesRegex(ValueError, "signature"):
            c.dex_classes(damaged)

    def test_all_truncations_rejected(self):
        good = make_dex()
        for size in range(len(good)):
            with self.subTest(size=size), self.assertRaises(ValueError):
                c.dex_classes(good[:size])

    def test_header_count_offset_bounds(self):
        good = make_dex()
        for offset, value in [(32, 0xffffffff), (36, 120), (40, 0x78563412),
                              (56, 0xffffffff), (60, 0xfffffffc), (64, 0xffffffff),
                              (96, 0xffffffff), (100, 0xfffffffc), (104, 0xffffffff),
                              (108, 4), (52, 0xfffffffc)]:
            with self.subTest(offset=offset), self.assertRaises(ValueError):
                c.dex_classes(mutate(good, offset, value))

    def test_table_overlap_rejected(self):
        good = make_dex()
        with self.assertRaisesRegex(ValueError, "Overlapping"):
            c.dex_classes(mutate(good, 68, 112))

    def test_class_and_string_indices_bounds(self):
        good = make_dex()
        types = struct.unpack_from("<I", good, 68)[0]
        classes = struct.unpack_from("<I", good, 100)[0]
        strings = struct.unpack_from("<I", good, 60)[0]
        for offset in (types, classes, classes + 8, classes + 12, classes + 16,
                       classes + 20, classes + 24, classes + 28, strings):
            with self.subTest(offset=offset), self.assertRaises(ValueError):
                c.dex_classes(mutate(good, offset, 0xfffffffe))

    def test_map_huge_count_and_duplicate(self):
        good = make_dex()
        map_off = struct.unpack_from("<I", good, 52)[0]
        with self.assertRaises(ValueError):
            c.dex_classes(mutate(good, map_off, 0xffffffff))
        changed = bytearray(good)
        struct.pack_into("<H", changed, map_off + 4 + 12, 0)
        with self.assertRaisesRegex(ValueError, "duplicate DEX map"):
            c.dex_classes(seal_dex(changed))

    def test_version_041_container_not_silently_partial(self):
        changed = bytearray(make_dex())
        changed[4:7] = b"041"
        with self.assertRaisesRegex(ValueError, "041"):
            c.dex_classes(seal_dex(changed))

    def test_uleb_overflow_truncation(self):
        for data in (b"", b"\x80", b"\xff" * 5, b"\xff\xff\xff\xff\x10"):
            with self.subTest(data=data), self.assertRaises(ValueError):
                c.uleb128(data, 0)

    def test_mutf8_bounds_and_encoding(self):
        for data in (b"\xff\xff\xff\xff\x0f", b"\x01a", b"\x02a\0", b"\x01\xc0\x81\0",
                     b"\x01\xe0\x80\x80\0", b"\x01\xf0\x90\x80\x80\0", b"\x01\xc2X\0"):
            with self.subTest(data=data), self.assertRaises(ValueError):
                c.dex_string(data, 0, 0)
        self.assertEqual(c.dex_string(b"\x01\xc0\x80\0", 0, 0), "\0")
        self.assertEqual(c.dex_string(b"\x02\xed\xa0\xbd\xed\xb8\x80\0", 0, 0), "\ud83d\ude00")


class ZipTests(unittest.TestCase):
    def test_duplicate_path(self):
        output = io.BytesIO()
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(output, "w") as archive:
                archive.writestr("a", b"one")
                archive.writestr("a", b"two")
        with self.assertRaisesRegex(ValueError, "Duplicate ZIP"):
            c.SafeZip(io.BytesIO(output.getvalue()))

    def test_unsafe_names(self):
        for name in ("../x", "/x", "a/../b", "a//b", "./a", "a\\b", "C:/a", "a\0b", "a\nb"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                c.safe_name(name)

    def test_symlink(self):
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w") as archive:
            info = zipfile.ZipInfo("link")
            info.create_system = 3
            info.external_attr = 0o120777 << 16
            archive.writestr(info, b"target")
        with self.assertRaisesRegex(ValueError, "symlink"):
            c.SafeZip(io.BytesIO(output.getvalue()))

    def test_local_central_name_mismatch(self):
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w") as archive:
            archive.writestr("abc", b"payload")
        changed = bytearray(output.getvalue())
        changed[30:33] = b"def"
        with self.assertRaisesRegex(ValueError, "name mismatch"):
            c.SafeZip(io.BytesIO(changed))

    def test_expansion_limits(self):
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w") as archive:
            archive.writestr("data", b"12345")
        with patch.object(c, "MAX_ENTRY", 4), self.assertRaisesRegex(ValueError, "Oversized"):
            c.SafeZip(io.BytesIO(output.getvalue()))


class ApkTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "fixture.apk"
        self.ref = reference()

    def check(self, members=None, **kwargs):
        write_zip(self.path, apk_members(self.ref) if members is None else members, **kwargs)
        return c.check_apk(self.path, self.ref)

    def test_universal_and_each_split(self):
        for abis in (("arm64-v8a", "x86_64"), ("arm64-v8a",), ("x86_64",)):
            with self.subTest(abis=abis):
                result = self.check(apk_members(self.ref, abis))
                self.assertEqual(result["abis"], sorted(abis))
                self.assertEqual(result["sherpa_class_definition_count"], 1)
                self.assertEqual(result["notice_count_verified"], 2)
                self.assertFalse(result["runtime_tested"])
                self.assertFalse(result["native_execution_tested"])

    def test_corrupt_native_bytes(self):
        members = apk_members(self.ref)
        name = "lib/arm64-v8a/libsherpa-onnx-jni.so"
        members[name] += b"unapproved change"
        with self.assertRaisesRegex(ValueError, "native bytes differ"):
            self.check(members)

    def test_missing_native_and_extra_bridges(self):
        members = apk_members(self.ref)
        del members["lib/x86_64/libonnxruntime.so"]
        with self.assertRaisesRegex(ValueError, "inventory mismatch"):
            self.check(members)
        for name in ("lib/arm64-v8a/libsherpa-onnx-c-api.so", "lib/x86_64/libonnxruntime4j_jni.so",
                     "assets/libsherpa-copy.so"):
            members = apk_members(self.ref)
            members[name] = make_elf()
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "inventory mismatch"):
                self.check(members)

    def test_unsupported_abi(self):
        members = apk_members(self.ref)
        members["lib/armeabi-v7a/other.so"] = b"unsupported"
        with self.assertRaisesRegex(ValueError, "native path/ABI"):
            self.check(members)

    def test_missing_and_modified_attribution(self):
        members = apk_members(self.ref)
        del members[c.NOTICE_PREFIX + "attribution.txt"]
        with self.assertRaisesRegex(ValueError, "Missing AAR attribution"):
            self.check(members)
        members = apk_members(self.ref)
        members[c.NOTICE_PREFIX + "NOTICE.txt"] = b"changed"
        with self.assertRaisesRegex(ValueError, "Changed AAR attribution"):
            self.check(members)

    def test_extra_sherpa_and_ort_definitions(self):
        for name in (TTS, "Lcom/k2fsa/sherpa/onnx/GeneratedAudio;",
                     "Lcom/k2fsa/sherpa/onnx/OfflineSpeakerDiarization;", ORT):
            members = apk_members(self.ref)
            members["classes.dex"] = make_dex([ASR, name])
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "class set mismatch|ORT Java"):
                self.check(members)

    def test_exact_empty_generated_r_is_separate_from_aar_classes(self):
        for constructor in (False, True):
            for instructions in (None, (0x0176, 1, 0, 0x000e)):
                members = apk_members(self.ref)
                members["classes.dex"] = make_resource_dex(constructor=constructor, instructions=instructions)
                with self.subTest(constructor=constructor, instructions=instructions):
                    result = self.check(members)
                    self.assertEqual(result["sherpa_class_definition_count"], 1)
                    self.assertEqual(result["total_class_definition_count"], 2)
                    self.assertEqual(result["generated_resource_classes_verified"], [RESOURCE])

    def test_generated_r_has_no_blanket_namespace_exception(self):
        for name in ("Lcom/k2fsa/sherpa/onnx/R$layout;", "Lcom/k2fsa/sherpa/onnx/R2;"):
            members = apk_members(self.ref)
            members["classes.dex"] = make_dex([ASR, name])
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "class set mismatch"):
                self.check(members)

    def test_generated_r_rejects_fields_interfaces_and_non_object_superclass(self):
        for options in ({"field_count": 1}, {"interface": True}, {"superclass": ASR},
                        {"virtual_count": 1}, {"direct_count": 2}, {"class_flags": 0x201}):
            members = apk_members(self.ref)
            members["classes.dex"] = make_resource_dex(**options)
            with self.subTest(options=options), self.assertRaisesRegex(ValueError, "Generated R"):
                self.check(members)

    def test_generated_r_rejects_arbitrary_or_native_methods_and_effectful_constructor(self):
        for options in ({"method_name": "run"}, {"method_name": "<clinit>"},
                        {"method_flags": 0x10101}, {"method_flags": 0x10401},
                        {"method_flags": 0x10009}, {"instructions": (0x1071, 1, 0, 0x000e)},
                        {"instructions": (0x1070, 0, 0, 0x000e)},
                        {"instructions": (0x1070, 1, 0, 0x000f)},
                        {"code_override": 0xfffffffc}):
            members = apk_members(self.ref)
            members["classes.dex"] = make_resource_dex(constructor=True, **options)
            with self.subTest(options=options), self.assertRaisesRegex(ValueError, "Generated R"):
                self.check(members)

    def test_generated_r_rejects_malformed_class_data_and_method_indices(self):
        good = make_resource_dex(constructor=True)
        class_off = struct.unpack_from("<I", good, 100)[0] + 32
        class_data = struct.unpack_from("<I", good, class_off + 24)[0]
        method_off = struct.unpack_from("<I", good, 92)[0]
        damaged = []
        for offset, value in ((class_off + 8, 0xffffffff), (class_off + 24, len(good) - 1),
                              (class_off + 20, class_data), (class_off + 28, class_data)):
            damaged.append(mutate(good, offset, value))
        invalid_method = bytearray(good)
        invalid_method[class_data + 4] = 127
        damaged.append(seal_dex(invalid_method))
        invalid_proto = bytearray(good)
        struct.pack_into("<H", invalid_proto, method_off + 2, 65535)
        damaged.append(seal_dex(invalid_proto))
        for dex in damaged:
            members = apk_members(self.ref)
            members["classes.dex"] = dex
            with self.subTest(digest=c.sha256(dex)), self.assertRaises(ValueError):
                self.check(members)

    def test_references_and_plain_strings_do_not_count_as_classes(self):
        members = apk_members(self.ref)
        members["classes.dex"] = make_dex([ASR], [TTS, ORT], ["GeneratedAudio"])
        self.assertEqual(self.check(members)["sherpa_class_definition_count"], 1)

    def test_class_missing_despite_descriptor_string(self):
        members = apk_members(self.ref)
        members["classes.dex"] = make_dex(["Lapp/Other;"], [ASR])
        with self.assertRaisesRegex(ValueError, "class set mismatch"):
            self.check(members)

    def test_multidex_definition_and_duplicate(self):
        members = apk_members(self.ref)
        members["classes2.dex"] = make_dex(["Lapp/Other;"])
        self.assertEqual(self.check(members)["total_class_definition_count"], 2)
        members["classes2.dex"] = make_dex([ASR])
        with self.assertRaisesRegex(ValueError, "across DEX"):
            self.check(members)

    def test_noncanonical_dex_rejected(self):
        for name in ("classes1.dex", "classes01.dex", "assets/hidden.dex"):
            members = apk_members(self.ref)
            members[name] = make_dex([TTS])
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "DEX member"):
                self.check(members)

    def test_alignment_measured_stored_compressed_and_aligned(self):
        result = self.check()
        self.assertFalse(result["stored_native_zip_16k_pass"])
        self.assertTrue(any(m["zip_offset_mod_16384"] for m in result["native_members"]))
        result = self.check(compression=zipfile.ZIP_DEFLATED)
        self.assertTrue(all(m["zip_16k_aligned"] is None for m in result["native_members"]))
        self.assertFalse(result["external_zipalign_run"])
        result = self.check(align=True)
        self.assertTrue(result["stored_native_zip_16k_pass"])
        self.assertTrue(all(m["zip_16k_aligned"] for m in result["native_members"]))

    def test_other_native_load_alignment_is_not_certified(self):
        members = apk_members(self.ref)
        members["lib/arm64-v8a/libother.so"] = make_elf(align=4096)
        result = self.check(members)
        self.assertEqual(result["other_native_load_16k_failures"], ["lib/arm64-v8a/libother.so"])

    def test_elf_bounds_architecture_and_load(self):
        good = make_elf()
        for data in (good[:50], good[:150], make_elf("x86_64")):
            with self.subTest(length=len(data)), self.assertRaises(ValueError):
                c.elf_report(data, "arm64-v8a")
        self.assertFalse(c.elf_report(make_elf(align=4096), "arm64-v8a")[0]["load_16k_pass"])

    def test_aar_pin_is_not_overridable_by_cli(self):
        self.path.write_bytes(b"not the pinned AAR")
        with self.assertRaisesRegex(ValueError, "immutable FN-74 pin"):
            c.load_reference(self.path)
        with contextlib.redirect_stdout(io.StringIO()) as output:
            status = c.main(["--aar", str(self.path), str(self.path)])
        self.assertEqual(status, 1)
        self.assertEqual(json.loads(output.getvalue())["status"], "failed")

    def test_cli_all_variants_guard(self):
        paths = []
        for index, abis in enumerate((("arm64-v8a", "x86_64"), ("arm64-v8a",), ("x86_64",))):
            path = self.path.with_name(f"fixture-{index}.apk")
            write_zip(path, apk_members(self.ref, abis))
            paths.append(str(path))
        with patch.object(c, "load_reference", return_value=self.ref):
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(c.main(["--aar", "unused", "--require-all-variants", *paths]), 0)
                self.assertEqual(c.main(["--aar", "unused", "--require-all-variants", *paths[:2]]), 1)


@unittest.skipUnless(os.environ.get("FN74_SOURCE_AAR"), "Set FN74_SOURCE_AAR to test the real pinned artifact")
class RealAarTests(unittest.TestCase):
    def test_pinned_aar_and_structural_apk(self):
        ref = c.load_reference(Path(os.environ["FN74_SOURCE_AAR"]))
        self.assertEqual((len(ref["classes"]), ref["native_declaration_count"], ref["notice_count"], len(ref["assets"])),
                         (48, 32, 29, 37))
        for abi in c.ABIS:
            jni = ref["native_info"][f"{abi}/libsherpa-onnx-jni.so"]
            self.assertEqual(jni["defined_jni_exports"], 117)
            self.assertEqual(jni["additional_jni_exports"], 85)
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "synthetic-not-installable.apk"
            write_zip(path, apk_members(ref), align=True)
            result = c.check_apk(path, ref)
            self.assertEqual(result["sherpa_class_definition_count"], 48)
            self.assertEqual(result["notice_count_verified"], 29)
            self.assertEqual(result["attribution_and_provenance_assets_verified"], 37)
            self.assertTrue(result["source_profile_supplement_verified"])
            self.assertTrue(result["stored_native_zip_16k_pass"])
            self.assertTrue(any("RELRO" in text and "no runtime failure" in text for text in result["warnings"]))
            self.assertFalse(result["native_execution_tested"])


if __name__ == "__main__":
    unittest.main()
