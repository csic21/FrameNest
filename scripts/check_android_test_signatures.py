#!/usr/bin/env python3
"""Check compiled JUnit4 Android method descriptors before any device run.

python3 scripts/check_android_test_signatures.py app/build --path-contains androidtest
Reads JVM class files directly, without loading Android classes or native code.
Missing compiled tests or the required runtime-comparison class fail closed.
"""
import argparse
from pathlib import Path


TEST = "Lorg/junit/Test;"
INSTANCE_HOOKS = {"Lorg/junit/Before;", "Lorg/junit/After;"}
STATIC_HOOKS = {"Lorg/junit/BeforeClass;", "Lorg/junit/AfterClass;"}
REQUIRED = "com/framenest/feature/listen_translate/SenseVoiceRuntimeComparisonTest"


class Reader:
    def __init__(self, data):
        self.data, self.position = data, 0

    def take(self, count):
        end = self.position + count
        if end > len(self.data):
            raise ValueError("Truncated class file")
        value = self.data[self.position:end]
        self.position = end
        return value

    def number(self, size):
        return int.from_bytes(self.take(size), "big")


def parse_class(data):
    reader = Reader(data)
    if reader.number(4) != 0xCAFEBABE:
        raise ValueError("Not a JVM class file")
    reader.take(4)  # minor/major version
    pool = [None] * reader.number(2)
    index = 1
    while index < len(pool):
        tag = reader.number(1)
        if tag == 1:
            pool[index] = reader.take(reader.number(2)).decode("utf-8", errors="replace")
        elif tag in (7, 8, 16, 19, 20):
            pool[index] = reader.number(2)
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            reader.take(4)
        elif tag in (5, 6):
            reader.take(8)
            index += 1
        elif tag == 15:
            reader.take(3)
        else:
            raise ValueError(f"Unsupported constant-pool tag {tag}")
        index += 1
    access = reader.number(2)
    name = pool[pool[reader.number(2)]]
    reader.take(2)  # superclass
    reader.take(reader.number(2) * 2)  # interfaces

    def annotation(r):
        kind = pool[r.number(2)]
        for _ in range(r.number(2)):
            r.take(2)
            element(r)
        return kind

    def element(r):
        tag = chr(r.number(1))
        if tag in "BCDFIJSZsc":
            r.take(2)
        elif tag == "e":
            r.take(4)
        elif tag == "@":
            annotation(r)
        elif tag == "[":
            for _ in range(r.number(2)):
                element(r)
        else:
            raise ValueError(f"Unsupported annotation value {tag}")

    def attributes():
        annotations = set()
        for _ in range(reader.number(2)):
            key = pool[reader.number(2)]
            value = reader.take(reader.number(4))
            if key in ("RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations"):
                nested = Reader(value)
                annotations.update(annotation(nested) for _ in range(nested.number(2)))
        return annotations

    def members():
        result = []
        for _ in range(reader.number(2)):
            flags, member_name, descriptor = reader.number(2), pool[reader.number(2)], pool[reader.number(2)]
            result.append({"access": flags, "name": member_name, "descriptor": descriptor,
                           "annotations": attributes()})
        return result

    members()  # fields
    methods = members()
    attributes()
    if reader.position != len(data):
        raise ValueError("Unexpected trailing class-file bytes")
    return {"name": name, "access": access, "methods": methods}


def validate_class(parsed):
    methods = parsed["methods"]
    tests = [method for method in methods if TEST in method["annotations"]]
    errors = []
    if not tests:
        return 0, errors
    name = parsed["name"].replace("/", ".")
    # Other runners can legitimately use parameterized constructors or abstract
    # inherited test bases. The runtime comparison uses plain AndroidJUnit4.
    if parsed["name"] == REQUIRED:
        if not parsed["access"] & 0x0001 or parsed["access"] & 0x0600:
            errors.append(f"{name}: runtime-comparison class must be public and concrete")
        constructors = [m for m in methods if m["name"] == "<init>" and m["access"] & 0x0001]
        if len(constructors) != 1 or constructors[0]["descriptor"] != "()V":
            errors.append(f"{name}: runtime-comparison class requires one public zero-argument constructor")
    for method in methods:
        annotations = method["annotations"]
        if not annotations & ({TEST} | INSTANCE_HOOKS | STATIC_HOOKS):
            continue
        flags = method["access"]
        expect_static = bool(annotations & STATIC_HOOKS)
        if (method["descriptor"] != "()V" or not flags & 0x0001
                or bool(flags & 0x0008) != expect_static):
            errors.append(f"{name}.{method['name']}: expected public "
                          f"{'static ' if expect_static else 'instance '}void zero-argument method; "
                          f"descriptor={method['descriptor']} access=0x{flags:04x}")
    return len(tests), errors


def check_directory(root, path_contains="", required=REQUIRED):
    classes, tests, found, errors = 0, 0, False, []
    for path in sorted(root.rglob("*.class")):
        if path_contains.lower() not in str(path).lower():
            continue
        parsed = parse_class(path.read_bytes())
        count, failures = validate_class(parsed)
        if count:
            classes += 1
            tests += count
            found |= parsed["name"] == required
        errors.extend(failures)
    if not tests:
        errors.append("No compiled JUnit4 @Test methods found; compile instrumentation first")
    if not found:
        errors.append(f"Required compiled JUnit4 test class is missing: {required}")
    if errors:
        raise ValueError("\n".join(errors))
    return classes, tests


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("classes_root", type=Path)
    parser.add_argument("--path-contains", default="")
    args = parser.parse_args()
    classes, tests = check_directory(args.classes_root, args.path_contains)
    print(f"PASS: {tests} compiled JUnit4 @Test signatures in {classes} classes; public void, zero arguments")


if __name__ == "__main__":
    main()
