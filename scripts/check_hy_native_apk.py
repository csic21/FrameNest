#!/usr/bin/env python3
"""Check shipped JNI ABI presence and 16-KiB ELF LOAD alignment without execution."""
import struct
import sys
import zipfile


def verify_elf(data):
    if data[:5] != b'\x7fELF\x02' or data[5] != 1:
        raise ValueError('expected little-endian ELF64')
    phoff = struct.unpack_from('<Q', data, 32)[0]
    entsize, count = struct.unpack_from('<HH', data, 54)
    if entsize < 56 or not count:
        raise ValueError('missing program headers')
    loads = 0
    for i in range(count):
        offset = phoff + i * entsize
        kind, flags, fileoff, vaddr, paddr, filesz, memsz, align = struct.unpack_from('<IIQQQQQQ', data, offset)
        if kind == 1:
            loads += 1
            if align < 16384 or (vaddr - fileoff) % 16384:
                raise ValueError('LOAD is not 16-KiB compatible')
    if not loads:
        raise ValueError('no LOAD segments')


if __name__ == '__main__':
    with zipfile.ZipFile(sys.argv[1]) as apk:
        for abi in ('arm64-v8a', 'x86_64'):
            name = f'lib/{abi}/libframenest_hymt.so'
            verify_elf(apk.read(name))
            print(f'{abi}: JNI present, ELF LOAD alignment verified')
