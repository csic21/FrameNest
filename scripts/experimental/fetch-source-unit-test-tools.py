#!/usr/bin/env python3
"""Explicit opt-in test tools download from Maven Central; writes only a selected cache directory."""
import argparse
import hashlib
from pathlib import Path
import urllib.request

ARTIFACTS = [('fn70-kotlin', 'org/jetbrains/annotations/13.0/annotations-13.0.jar', 'ace2a10dc8e2d5fd34925ecac03e4988b2c0f851650c94b8cef49ba1bd111478'), ('fn70-kotlin', 'org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar', '66fdef91e9739348df7a096aa384a5685f4e875584cce89386a7a47251c4d8e9'), ('fn70-kotlin', 'junit/junit/4.13.2/junit-4.13.2.jar', '8e495b634469d64fb8acfa3495a065cbacc8a0fff55ce1e31007be4c16dc57d3'), ('fn70-kotlin', 'org/jetbrains/kotlin/kotlin-compiler-embeddable/2.1.20/kotlin-compiler-embeddable-2.1.20.jar', 'c54a00718c8c0e3ee858bf42771c7f401c5e3c1738861e18e9536371042fa1b3'), ('fn70-kotlin', 'org/jetbrains/kotlin/kotlin-daemon-embeddable/2.1.20/kotlin-daemon-embeddable-2.1.20.jar', 'd9e83df1d847a201ba3c016a786ced091be9503997d09f6a9cf1796ee489f374'), ('fn70-kotlin', 'org/jetbrains/kotlin/kotlin-reflect/1.6.10/kotlin-reflect-1.6.10.jar', '3277ac102ae17aad10a55abec75ff5696c8d109790396434b496e75087854203'), ('fn70-kotlin', 'org/jetbrains/kotlin/kotlin-script-runtime/2.1.20/kotlin-script-runtime-2.1.20.jar', 'ae4397fbb3aa2a1ada09290e753bba99a4114548977c3115526b9958f4cb1a04'), ('fn70-kotlin', 'org/jetbrains/kotlin/kotlin-stdlib/2.1.20/kotlin-stdlib-2.1.20.jar', '1bcc74e8ce84e2c25eaafde10f1248349cce3062b6e36978cbeec610db1e930a'), ('fn70-kotlin', 'org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.0/kotlinx-coroutines-core-jvm-1.8.0.jar', '9860906a1937490bf5f3b06d2f0e10ef451e65b95b269f22daf68a3d1f5065c5'), ('fn70-kotlin', 'org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar', 'c5fd725bffab51846bf3c77db1383c60aaaebfe1b7fe2f00d23fe1b7df0a439d'), ('fn70-kuromoji', 'com/atilika/kuromoji/kuromoji-core/0.9.0/kuromoji-core-0.9.0.jar', '9dec317cab2879474d39bffea4f3f481288d87952c9b5e19d6897b0d5e0d3613'), ('fn70-kuromoji', 'com/atilika/kuromoji/kuromoji-ipadic/0.9.0/kuromoji-ipadic-0.9.0.jar', '24909fd751c0b439f7af5131b080eb65bc85062f0c4977ca4a71c76abe74e0b6')]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache-root", default="/tmp")
    args = parser.parse_args()
    root = Path(args.cache_root)
    for folder, path, expected in ARTIFACTS:
        destination = root / folder / path.rsplit("/", 1)[-1]
        destination.parent.mkdir(parents=True, exist_ok=True)
        if destination.exists() and hashlib.sha256(destination.read_bytes()).hexdigest() == expected:
            print("verified", destination)
            continue
        with urllib.request.urlopen("https://repo.maven.apache.org/maven2/" + path, timeout=120) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != expected:
            raise RuntimeError("Checksum mismatch: " + path)
        temporary = destination.with_suffix(".download")
        temporary.write_bytes(data)
        temporary.replace(destination)
        print("downloaded and verified", destination)

if __name__ == "__main__":
    main()
