#!/usr/bin/env python3
"""Verify experimental APK contents against the immutable FN-74 source AAR.

Static evidence only: no APK installation, native execution, performance, legal
clearance, or 4/16-KiB Android runtime acceptance is implied. DEX class identity
comes from class_defs, never from arbitrary strings or type references.
Format: https://source.android.com/docs/core/runtime/dex-format
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import stat
import struct
import sys
import zipfile
import zlib

AAR_SHA256 = "6a243d2da9a1f521a85ceeb30b51e00a660e2a1bfb05b79f6b591361af7c7503"
SUPPLEMENT_ASSET = "assets/source_asr_notices/SUPPLEMENTAL-NOTICE.txt"
SUPPLEMENT_SHA256 = "e2fa4a31a1b49181a284e278704f6d5ae40906a9f5fbd0ce625d8a909718460d"
SUPPLEMENT_FILE = Path(__file__).resolve().parents[1] / "app/src/sourceAsr" / SUPPLEMENT_ASSET
ABIS = {"arm64-v8a": 183, "x86_64": 62}
LIBRARIES = {"libsherpa-onnx-jni.so", "libonnxruntime.so"}
NOTICE_PREFIX = "assets/third_party_licenses/"
SHERPA_PREFIX = "Lcom/k2fsa/sherpa/onnx/"
GENERATED_RESOURCE_CLASS = SHERPA_PREFIX + "R;"
ORT_PREFIXES = ("Lai/onnxruntime/", "Lcom/microsoft/onnxruntime/", "Lorg/onnxruntime/")
PAGE = 16384
MAX_ENTRY = 512 * 1024 * 1024
MAX_TOTAL = 2 * 1024 * 1024 * 1024


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def file_sha256(path):
    with open(path, "rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def span(data, offset, size, label):
    require(0 <= offset <= len(data) and 0 <= size <= len(data) - offset,
            f"{label}: out of bounds")
    return memoryview(data)[offset:offset + size]


def unpack(fmt, data, offset, label):
    return struct.unpack(fmt, span(data, offset, struct.calcsize(fmt), label))


def safe_name(name):
    require(bool(name) and "\\" not in name and ":" not in name
            and not any(ord(c) < 32 or ord(c) == 127 for c in name),
            f"Unsafe ZIP path: {name!r}")
    require(all(p not in ("", ".", "..") for p in name.rstrip("/").split("/")),
            f"Unsafe ZIP path: {name!r}")


class SafeZip:
    """Read-only archive with canonical names, bounded expansion and local checks."""
    def __init__(self, source):
        self.zip = zipfile.ZipFile(source)
        try:
            infos = self.zip.infolist()
            require(len(infos) <= 200000, "Too many ZIP entries")
            self.entries = {}
            self.offsets = {}
            total = 0
            regions = []
            for info in infos:
                safe_name(info.orig_filename)
                require(info.filename == info.orig_filename, "Truncated ZIP name")
                require(info.filename not in self.entries, "Duplicate ZIP entry: " + info.filename)
                require(not info.flag_bits & 1, "Encrypted ZIP entry")
                require(info.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED),
                        "Unsupported ZIP compression")
                mode = stat.S_IFMT(info.external_attr >> 16)
                require(mode in (0, stat.S_IFREG, stat.S_IFDIR), "Special/symlink ZIP entry")
                require(0 <= info.file_size <= MAX_ENTRY, "Oversized ZIP entry")
                total += info.file_size
                require(total <= MAX_TOTAL, "Oversized expanded ZIP")
                self.zip.fp.seek(info.header_offset)
                header = self.zip.fp.read(30)
                require(len(header) == 30, "Truncated ZIP local header")
                fields = struct.unpack("<4s5H3I2H", header)
                require(fields[0] == b"PK\x03\x04", "Bad ZIP local header")
                require(fields[2] == info.flag_bits and fields[3] == info.compress_type,
                        "ZIP local/central flags or method mismatch")
                raw_name = self.zip.fp.read(fields[-2])
                local_name = raw_name.decode("utf-8" if info.flag_bits & 0x800 else "cp437")
                require(local_name == info.filename, "ZIP local/central name mismatch")
                start = info.header_offset + 30 + fields[-2] + fields[-1]
                end = start + info.compress_size
                require(info.header_offset >= 0 and end <= self.zip.start_dir,
                        "ZIP entry extends into central directory")
                regions.append((info.header_offset, end))
                self.entries[info.filename] = info
                self.offsets[info.filename] = start
            for previous, current in zip(sorted(regions), sorted(regions)[1:]):
                require(previous[1] <= current[0], "Overlapping ZIP entries")
        except Exception:
            self.zip.close()
            raise

    def read(self, name):
        require(name in self.entries, "Missing ZIP entry: " + name)
        return self.zip.read(self.entries[name])  # Also checks CRC and overlap.

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.zip.close()


def uleb128(data, offset):
    value = 0
    for i in range(5):
        byte = span(data, offset + i, 1, "DEX uleb128")[0]
        require(i < 4 or byte < 16, "DEX uleb128 overflow")
        value |= (byte & 127) << (7 * i)
        if byte < 128:
            return value, offset + i + 1
    raise ValueError("DEX unterminated uleb128")


def dex_string(data, offset, data_start):
    require(data_start <= offset < len(data), "DEX string offset outside data")
    length, pos = uleb128(data, offset)
    require(length <= len(data) - pos, "DEX string length out of bounds")
    units = []
    # MUTF-8 encodes UTF-16 code units; 4-byte UTF-8 and invalid/overlong
    # sequences are rejected. Only requested class descriptors are decoded.
    while True:
        first = span(data, pos, 1, "DEX string terminator")[0]
        pos += 1
        if first == 0:
            break
        if first < 128:
            unit = first
        elif 0xC0 <= first <= 0xDF:
            second = span(data, pos, 1, "DEX MUTF-8")[0]
            pos += 1
            require(second & 0xC0 == 0x80, "Invalid DEX MUTF-8 continuation")
            unit = ((first & 31) << 6) | (second & 63)
            require(unit >= 128 or (first == 0xC0 and second == 0x80),
                    "Overlong DEX MUTF-8")
        elif 0xE0 <= first <= 0xEF:
            second, third = span(data, pos, 2, "DEX MUTF-8")
            pos += 2
            require(second & 0xC0 == 0x80 and third & 0xC0 == 0x80,
                    "Invalid DEX MUTF-8 continuation")
            unit = ((first & 15) << 12) | ((second & 63) << 6) | (third & 63)
            require(unit >= 2048, "Overlong DEX MUTF-8")
        else:
            raise ValueError("Invalid DEX MUTF-8 leading byte")
        units.append(chr(unit))
        require(len(units) <= length, "DEX string length mismatch")
    require(len(units) == length, "DEX string length mismatch")
    return "".join(units)


def dex_classes(data):
    """Read bounded standard DEX 035/037..040 class definitions (not bytecode)."""
    require(data[:8] in [b"dex\n" + v + b"\0" for v in (b"035", b"037", b"038", b"039", b"040")],
            "Unsupported DEX magic/version (041 containers are not silently accepted)")
    header = unpack("<20I", data, 32, "DEX header")
    file_size, header_size, endian, link_size, link_off, map_off = header[:6]
    require(file_size == len(data) and header_size == 112 and endian == 0x12345678,
            "Invalid DEX size/header/endianness")
    require(zlib.adler32(data[12:]) & 0xffffffff == unpack("<I", data, 8, "DEX checksum")[0],
            "DEX checksum mismatch")
    require(hashlib.sha1(data[32:]).digest() == data[12:32], "DEX signature mismatch")
    require(link_size == 0 and link_off == 0, "Linked DEX is unsupported")
    tables = []
    regions = [(0, 112)]
    for i, (width, label) in enumerate(((4, "string_ids"), (4, "type_ids"),
                                      (12, "proto_ids"), (8, "field_ids"),
                                      (8, "method_ids"), (32, "class_defs"))):
        count, offset = header[6 + i * 2:8 + i * 2]
        require((count == 0 and offset == 0) or (count > 0 and offset >= 112 and offset % 4 == 0),
                f"Invalid DEX {label} offset")
        span(data, offset, count * width, "DEX " + label)
        if count:
            regions.append((offset, offset + count * width))
        tables.append((count, offset))
    data_size, data_off = header[18:20]
    require(data_off >= 112 and data_off % 4 == 0 and data_size > 0
            and data_off + data_size == len(data), "Invalid DEX data section")
    regions.append((data_off, len(data)))
    ordered = sorted(regions)
    require(all(a[1] <= b[0] for a, b in zip(ordered, ordered[1:])), "Overlapping DEX sections")
    require(data_off <= map_off < len(data) and map_off % 4 == 0, "Invalid DEX map offset")
    map_count = unpack("<I", data, map_off, "DEX map")[0]
    span(data, map_off + 4, map_count * 12, "DEX map list")
    map_types = set()
    last_offset = -1
    mapped = {}
    for i in range(map_count):
        kind, unused, count, offset = unpack("<HHII", data, map_off + 4 + 12 * i, "DEX map item")
        require(unused == 0 and count > 0 and kind not in map_types and last_offset < offset < len(data),
                "Invalid/duplicate DEX map item")
        map_types.add(kind)
        last_offset = offset
        mapped[kind] = (count, offset)
    require(mapped.get(0) == (1, 0) and mapped.get(0x1000) == (1, map_off), "DEX map/header mismatch")
    for kind, table in enumerate(tables, 1):
        require((table[0] == 0 and kind not in mapped) or mapped.get(kind) == table,
                "DEX map/table mismatch")
    strings_count, strings_off = tables[0]
    types_count, types_off = tables[1]
    classes_count, classes_off = tables[5]
    require(types_count <= 65535, "Too many DEX type IDs")
    type_strings = []
    for i in range(types_count):
        index = unpack("<I", data, types_off + i * 4, "DEX type ID")[0]
        require(index < strings_count and (not type_strings or index > type_strings[-1]),
                "Invalid DEX type string index/order")
        type_strings.append(index)

    def string(index):
        require(index < strings_count, "DEX string index out of bounds")
        offset = unpack("<I", data, strings_off + index * 4, "DEX string ID")[0]
        return dex_string(data, offset, data_off)

    def type_name(index):
        require(index < types_count, "DEX type index out of bounds")
        return string(type_strings[index])

    def constructor(index, owner):
        count, offset = tables[4]
        require(index < count, "Generated R method index out of bounds")
        cls, proto, name = unpack("<HHI", data, offset + index * 8, "Generated R method ID")
        require(type_name(cls) == owner and string(name) == "<init>",
                "Generated R permits only its constructor and Object.<init>")
        count, offset = tables[2]
        require(proto < count, "Generated R prototype index out of bounds")
        shorty, returns, parameters = unpack("<III", data, offset + proto * 12, "Generated R prototype")
        require(string(shorty) == "V" and type_name(returns) == "V",
                "Generated R constructor must return void")
        if parameters:
            require(data_off <= parameters < len(data) and parameters % 4 == 0,
                    "Generated R parameters offset out of bounds")
            require(unpack("<I", data, parameters, "Generated R parameters")[0] == 0,
                    "Generated R constructor must have no parameters")

    def generated_resource(flags, superclass, interfaces, annotations, class_data, static_values):
        # AGP generates this exact, empty resource holder even when R.txt is
        # empty. It is not one of the 48 classes compiled into classes.jar.
        # Do not widen this exception to R$*, arbitrary methods or native APIs.
        require(flags & ~(0x1 | 0x10 | 0x1000) == 0,
                "Generated R has unexpected class flags")
        require(type_name(superclass) == "Ljava/lang/Object;", "Generated R superclass must be Object")
        require(not interfaces or unpack("<I", data, interfaces, "Generated R interfaces")[0] == 0,
                "Generated R must have no interfaces")
        require(annotations == 0 and static_values == 0,
                "Generated R must have no annotations or static values")
        if not class_data:
            return
        counts = []
        pos = class_data
        for _ in range(4):
            value, pos = uleb128(data, pos)
            counts.append(value)
        static_fields, instance_fields, direct_methods, virtual_methods = counts
        require(static_fields == instance_fields == virtual_methods == 0 and direct_methods <= 1,
                "Generated R must have no fields and at most one direct constructor")
        if not direct_methods:
            return
        method, pos = uleb128(data, pos)
        access, pos = uleb128(data, pos)
        code, pos = uleb128(data, pos)
        require(access in (0x10000, 0x10001, 0x10002, 0x10004),
                "Generated R constructor must be non-native, non-static and concrete")
        constructor(method, GENERATED_RESOURCE_CLASS)
        require(data_off <= code < len(data) and code % 4 == 0,
                "Generated R constructor code offset out of bounds")
        registers, ins, outs, tries, debug, size = unpack("<4H2I", data, code, "Generated R code")
        require((registers, ins, outs, tries, size) == (1, 1, 1, 0, 4),
                "Generated R constructor must only invoke Object.<init> and return")
        require(debug == 0 or data_off <= debug < len(data), "Generated R debug offset out of bounds")
        invoke, target, args, returns = unpack("<4H", data, code + 16, "Generated R instructions")
        require(invoke in (0x1070, 0x0176) and args == 0 and returns == 0x000e,
                "Generated R constructor must only invoke Object.<init> and return")
        constructor(target, "Ljava/lang/Object;")

    classes = set()
    for i in range(classes_count):
        index, flags, superclass, interfaces, source, annotations, class_data, static_values = unpack(
            "<8I", data, classes_off + i * 32, "DEX class definition")
        require(index < types_count and (superclass == 0xffffffff or superclass < types_count)
                and (source == 0xffffffff or source < strings_count), "DEX class index out of bounds")
        for offset in (interfaces, annotations, class_data, static_values):
            require(offset == 0 or data_off <= offset < len(data), "DEX class data offset out of bounds")
        if interfaces:
            require(interfaces % 4 == 0, "Unaligned DEX interfaces")
            count = unpack("<I", data, interfaces, "DEX interfaces")[0]
            values = span(data, interfaces + 4, count * 2, "DEX interfaces")
            require(all(x[0] < types_count for x in struct.iter_unpack("<H", values)),
                    "DEX interface type index out of bounds")
        name = type_name(index)
        require(name.startswith("L") and name.endswith(";") and len(name) > 2
                and all(p for p in name[1:-1].split("/"))
                and not any(c in ".;[" or ord(c) < 33 for c in name[1:-1]),
                "Invalid DEX class descriptor")
        require(name not in classes, "Duplicate DEX class definition: " + name)
        if name == GENERATED_RESOURCE_CLASS:
            generated_resource(flags, superclass, interfaces, annotations, class_data, static_values)
        classes.add(name)
    return classes


class ClassReader:
    def __init__(self, data):
        self.data, self.pos = data, 0

    def take(self, size):
        result = bytes(span(self.data, self.pos, size, "JVM classfile"))
        self.pos += size
        return result

    def uint(self, size):
        return int.from_bytes(self.take(size), "big")


def classfile(data):
    reader = ClassReader(data)
    require(reader.uint(4) == 0xcafebabe, "Invalid JVM classfile")
    reader.take(4)
    count = reader.uint(2)
    require(count > 1, "Empty JVM constant pool")
    pool = [None] * count
    index = 1
    while index < count:
        tag = reader.uint(1)
        if tag == 1:
            pool[index] = (tag, reader.take(reader.uint(2)))
        elif tag in (7, 8, 16, 19, 20):
            pool[index] = (tag, reader.uint(2))
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            reader.take(4)
        elif tag in (5, 6):
            reader.take(8)
            index += 1
            require(index < count, "Invalid wide JVM constant")
        elif tag == 15:
            reader.take(3)
        else:
            raise ValueError("Unsupported JVM constant tag")
        index += 1

    def cp(index, tag):
        require(0 < index < count and pool[index] is not None and pool[index][0] == tag,
                "Invalid JVM constant reference")
        return pool[index][1]

    def utf(index):
        return cp(index, 1).decode("utf-8")  # Names/descriptors are ASCII in this pinned AAR.

    reader.take(2)
    name = utf(cp(reader.uint(2), 7))
    reader.take(2)
    reader.take(reader.uint(2) * 2)

    def attrs():
        for _ in range(reader.uint(2)):
            cp(reader.uint(2), 1)
            reader.take(reader.uint(4))

    methods = []
    for group in ("fields", "methods"):
        for _ in range(reader.uint(2)):
            flags, member, descriptor = reader.uint(2), utf(reader.uint(2)), utf(reader.uint(2))
            attrs()
            if group == "methods" and flags & 0x100:
                methods.append((name, member, descriptor))
    attrs()
    require(reader.pos == len(data), "Trailing JVM classfile data")
    return "L" + name + ";", methods


def jni_escape(value):
    result = ""
    for c in value:
        if c.isascii() and c.isalnum():
            result += c
        elif c in "/_;[":
            result += {"/": "_", "_": "_1", ";": "_2", "[": "_3"}[c]
        else:
            raw = c.encode("utf-16-be")
            result += "".join("_0" + raw[i:i + 2].hex() for i in range(0, len(raw), 2))
    return result


def elf_report(data, abi, symbols=False):
    require(data[:7] == b"\x7fELF\x02\x01\x01", "Expected little-endian ELF64")
    require(unpack("<H", data, 18, "ELF machine")[0] == ABIS[abi], "ELF ABI mismatch")
    require(unpack("<H", data, 16, "ELF type")[0] == 3, "Expected shared ELF")
    phoff = unpack("<Q", data, 32, "ELF phoff")[0]
    phsize, phnum = unpack("<HH", data, 54, "ELF program header count")
    require(phsize == 56 and phnum > 0, "Invalid ELF program headers")
    span(data, phoff, phsize * phnum, "ELF program headers")
    loads, relro = [], []
    for i in range(phnum):
        kind, flags, offset, vaddr, paddr, filesz, memsz, align = unpack(
            "<IIQQQQQQ", data, phoff + i * phsize, "ELF program header")
        span(data, offset, filesz, "ELF segment")
        if kind == 1:
            require(memsz >= filesz, "Invalid ELF LOAD size")
            loads.append(align >= PAGE and align & (align - 1) == 0 and (vaddr - offset) % PAGE == 0)
        elif kind == 0x6474e552:
            relro.append((vaddr + memsz) % PAGE)
    require(bool(loads), "Missing ELF LOAD segments")
    result = {"load_16k_pass": all(loads), "load_count": len(loads),
              "relro_unrounded_end_mod_16384": relro}
    exports = set()
    if symbols:
        shoff = unpack("<Q", data, 40, "ELF shoff")[0]
        shsize, shnum = unpack("<HH", data, 58, "ELF section count")
        require(shsize == 64 and shnum > 0, "Missing ELF section table")
        span(data, shoff, shsize * shnum, "ELF sections")
        sections = [unpack("<IIQQQQIIQQ", data, shoff + i * shsize, "ELF section") for i in range(shnum)]
        dynsym_count = 0
        for section in sections:
            if section[1] != 11:  # SHT_DYNSYM
                continue
            dynsym_count += 1
            offset, size, link, entsize = section[4], section[5], section[6], section[9]
            require(entsize == 24 and size % 24 == 0 and link < shnum, "Invalid ELF dynsym")
            strings = sections[link]
            require(strings[1] == 3, "ELF dynsym has invalid string table")
            table = bytes(span(data, strings[4], strings[5], "ELF dynstr"))
            span(data, offset, size, "ELF symbols")
            for i in range(size // 24):
                name, info, other, defined, value, symbol_size = unpack(
                    "<IBBHQQ", data, offset + i * 24, "ELF symbol")
                require(name < len(table), "ELF symbol name out of bounds")
                end = table.find(b"\0", name)
                require(end >= 0, "Unterminated ELF symbol name")
                if defined != 0 and info >> 4 in (1, 2) and other & 3 in (0, 3):
                    symbol = table[name:end].decode("utf-8")
                    if symbol.startswith("Java_"):
                        exports.add(symbol)
        require(dynsym_count > 0, "Missing ELF dynamic symbols")
    return result, exports


def load_reference(path):
    require(file_sha256(path) == AAR_SHA256, "AAR SHA-256 differs from immutable FN-74 pin")
    with SafeZip(path) as aar:
        files = {name for name, info in aar.entries.items() if not info.is_dir()}
        expected = {f"jni/{abi}/{lib}" for abi in ABIS for lib in LIBRARIES}
        require({n for n in files if n.startswith("jni/")} == expected, "AAR native inventory mismatch")
        native = {name.removeprefix("jni/"): aar.read(name) for name in sorted(expected)}
        assets = {name: aar.read(name) for name in sorted(files) if name.startswith("assets/")}
        require(bool(assets), "AAR attribution assets absent")
        bundle = json.loads(assets[NOTICE_PREFIX + "license-bundle-manifest.json"])
        notice_names = set()
        for item in bundle:
            safe_name(item["path"])
            name = NOTICE_PREFIX + item["path"]
            require(name not in notice_names, "Duplicate notice manifest path")
            notice_names.add(name)
            require(name in assets and sha256(assets[name]) == item["sha256"], "AAR notice digest mismatch")
        classes, methods = set(), []
        with SafeZip(io.BytesIO(aar.read("classes.jar"))) as jar:
            for name in sorted(jar.entries):
                if not name.endswith(".class"):
                    continue
                descriptor, natives = classfile(jar.read(name))
                require(descriptor == "L" + name[:-6] + ";", "JAR class path/definition mismatch")
                require(descriptor.startswith(SHERPA_PREFIX) and descriptor not in classes,
                        "Unexpected/duplicate AAR class")
                classes.add(descriptor)
                methods.extend(natives)
        require(len(classes) == 48 and len(methods) == 32 and len(notice_names) == 29,
                "Pinned AAR class/native declaration/notice counts changed")
    native_info = {}
    for name, data in native.items():
        abi, lib = name.split("/")
        details, exports = elf_report(data, abi, symbols=lib == "libsherpa-onnx-jni.so")
        require(details["load_16k_pass"], "Pinned AAR ELF LOAD16K failure: " + name)
        if exports:
            require(not any(re.search(r"OfflineTts|GeneratedAudio|SpeakerDiarization|espeak|piper", x, re.I)
                            for x in exports), "Forbidden TTS/diarization JNI export")
            matched = set()
            for cls, method, descriptor in methods:
                short = "Java_" + jni_escape(cls) + "_" + jni_escape(method)
                long = short + "__" + jni_escape(descriptor.split(")", 1)[0][1:])
                overloaded = sum(c == cls and m == method for c, m, d in methods) > 1
                found = exports & ({long} if overloaded else {short, long})
                require(bool(found), "Missing JNI export for " + cls + "." + method)
                matched.update(found)
            details.update({"defined_jni_exports": len(exports),
                            "exports_matching_packaged_native_declarations": len(matched),
                            "additional_jni_exports": len(exports - matched)})
        native_info[name] = details
    # The AAR remains byte-identical. The app supplies an independently pinned
    # Eigen source-access / Android changed-file notice alongside its assets.
    supplement = SUPPLEMENT_FILE.read_bytes()
    require(len(supplement) == 2209 and sha256(supplement) == SUPPLEMENT_SHA256,
            "Source-profile supplemental notice differs from reviewed pin")
    assets[SUPPLEMENT_ASSET] = supplement
    return {"native": native, "native_info": native_info, "assets": assets,
            "classes": classes, "native_declaration_count": len(methods), "notice_count": len(notice_names)}


def check_apk(path, reference):
    with SafeZip(path) as apk:
        names = {n for n, info in apk.entries.items() if not info.is_dir()}
        native_names = sorted(n for n in names if n.startswith("lib/"))
        require(bool(native_names), "APK has no native libraries")
        abis = set()
        for name in native_names:
            parts = name.split("/")
            require(len(parts) == 3 and parts[1] in ABIS and parts[2].endswith(".so"),
                    "Unexpected APK native path/ABI: " + name)
            abis.add(parts[1])
        expected = {f"lib/{abi}/{lib}" for abi in abis for lib in LIBRARIES}
        related = {n for n in names if n.endswith(".so")
                   and re.search(r"sherpa|onnxruntime|espeak|piper", n, re.I)}
        require(related == expected, "APK sherpa/ORT native inventory mismatch: " + repr(sorted(related ^ expected)))
        members = []
        other_load_failures = []
        warnings = []
        for name in native_names:
            info = apk.entries[name]
            abi, lib = name.split("/")[1:]
            data = apk.read(name)
            details, _ = elf_report(data, abi)
            source = name.removeprefix("lib/")
            if name in expected:
                require(data == reference["native"][source], "APK native bytes differ from pinned AAR: " + name)
                details = reference["native_info"][source]
                if any(details["relro_unrounded_end_mod_16384"]):
                    warnings.append(name + ": retained unrounded RELRO-end static warning; no runtime failure demonstrated")
            elif not details["load_16k_pass"]:
                other_load_failures.append(name)
            stored = info.compress_type == zipfile.ZIP_STORED
            offset = apk.offsets[name]
            members.append({"path": name, "size_bytes": len(data), "sha256": sha256(data),
                            "matches_pinned_aar": name in expected, **details,
                            "zip_stored": stored, "zip_data_offset": offset,
                            "zip_offset_mod_16384": offset % PAGE,
                            "zip_16k_aligned": offset % PAGE == 0 if stored else None})
        for name, data in reference["assets"].items():
            require(name in names, "Missing AAR attribution/provenance asset: " + name)
            require(apk.read(name) == data, "Changed AAR attribution/provenance asset: " + name)
        dex_names = sorted(n for n in names if re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", n))
        require("classes.dex" in dex_names, "Missing classes.dex")
        require({n for n in names if n.endswith(".dex")} == set(dex_names), "Unexpected/noncanonical DEX member")
        classes, dex_reports = set(), []
        for name in dex_names:
            found = dex_classes(apk.read(name))
            require(not classes & found, "Duplicate class definitions across DEX files")
            classes.update(found)
            dex_reports.append({"path": name, "class_definition_count": len(found)})
        generated_resources = classes & {GENERATED_RESOURCE_CLASS}
        sherpa = {c for c in classes if c.startswith(SHERPA_PREFIX)} - generated_resources
        require(sherpa == reference["classes"], "DEX sherpa class set mismatch; missing="
                + repr(sorted(reference["classes"] - sherpa)) + "; extra=" + repr(sorted(sherpa - reference["classes"])))
        require(not any(c.startswith(ORT_PREFIXES) for c in classes), "Forbidden ORT Java class definition")
        unaligned = [m["path"] for m in members if m["zip_16k_aligned"] is False]
        if unaligned:
            warnings.append("Uncompressed native ZIP members are not all 16-KiB aligned; inspect zipalign result")
        if other_load_failures:
            warnings.append("Other app native libraries fail LOAD16K; this is not full-app 16-KiB acceptance")
        return {"path": str(path), "sha256": file_sha256(path), "size_bytes": Path(path).stat().st_size,
                "abis": sorted(abis), "native_members": members, "dex": dex_reports,
                "total_class_definition_count": len(classes), "sherpa_class_definition_count": len(sherpa),
                "generated_resource_classes_verified": sorted(generated_resources),
                "notice_count_verified": reference["notice_count"],
                "attribution_and_provenance_assets_verified": len(reference["assets"]),
                "source_profile_supplement_verified": SUPPLEMENT_ASSET in reference["assets"],
                "stored_native_zip_16k_pass": not unaligned,
                "external_zipalign_run": False, "other_native_load_16k_failures": other_load_failures,
                "runtime_tested": False, "native_execution_tested": False, "warnings": warnings}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aar", type=Path, required=True)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--require-all-variants", action="store_true")
    parser.add_argument("apks", nargs="+", type=Path)
    args = parser.parse_args(argv)
    try:
        reference = load_reference(args.aar)
        reports = [check_apk(path, reference) for path in args.apks]
        if args.require_all_variants:
            actual = [tuple(r["abis"]) for r in reports]
            expected = [("arm64-v8a",), ("x86_64",), ("arm64-v8a", "x86_64")]
            require(sorted(actual) == sorted(expected), "Require exactly universal, arm64-v8a and x86_64 APKs")
        report = {"status": "static-content-checks-passed", "aar_sha256": AAR_SHA256,
                  "aar_class_definition_count": len(reference["classes"]),
                  "aar_jvm_native_declaration_count": reference["native_declaration_count"],
                  "native_scope": "ASR-facing JVM classes; additional compiled native APIs remain. JNI export counts are not class counts or proof of dead-code removal.",
                  "runtime_tested": False, "native_execution_tested": False,
                  "full_app_16k_runtime_accepted": False, "apks": reports}
    except (ValueError, OSError, KeyError, UnicodeError, zipfile.BadZipFile, struct.error) as error:
        report = {"status": "failed", "error": str(error), "runtime_tested": False,
                  "native_execution_tested": False}
    output = json.dumps(report, separators=(",", ":"), sort_keys=True) + "\n"
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(output)
    print(output, end="")
    return 0 if report["status"] == "static-content-checks-passed" else 1


if __name__ == "__main__":
    sys.exit(main())
