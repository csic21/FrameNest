import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "check_android_test_signatures.py"
SPEC = importlib.util.spec_from_file_location("signature_check", SCRIPT)
check = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(check)


def class_bytes(descriptor="()V", access=0x0001, class_access=0x0021,
                constructor="()V", name=check.REQUIRED, annotation=check.TEST):
    """Small class-file metadata fixture; tests inspect bytes, never load it."""
    pool = []
    def utf(value):
        encoded = value.encode()
        pool.append(b"\x01" + struct.pack(">H", len(encoded)) + encoded)
        return len(pool)
    def clazz(value):
        index = utf(value)
        pool.append(b"\x07" + struct.pack(">H", index))
        return len(pool)
    current, parent = clazz(name), clazz("java/lang/Object")
    init, init_descriptor = utf("<init>"), utf(constructor)
    method, method_descriptor = utf("testMethod"), utf(descriptor)
    attribute, annotation_type = utf("RuntimeVisibleAnnotations"), utf(annotation)
    annotation_bytes = struct.pack(">HHH", 1, annotation_type, 0)
    methods = struct.pack(">HHHH", 1, init, init_descriptor, 0)
    methods += struct.pack(">HHHHHI", access, method, method_descriptor, 1, attribute, len(annotation_bytes))
    methods += annotation_bytes
    return (struct.pack(">IHHH", 0xCAFEBABE, 0, 61, len(pool) + 1) + b"".join(pool)
            + struct.pack(">HHHHHH", class_access, current, parent, 0, 0, 2)
            + methods + b"\0\0")


class AndroidTestSignaturesTest(unittest.TestCase):
    def test_reads_actual_annotation_descriptor_and_public_void_shape(self):
        parsed = check.parse_class(class_bytes())
        self.assertEqual(parsed["name"], check.REQUIRED)
        self.assertEqual(check.validate_class(parsed), (1, []))

    def test_regression_inferred_jsonobject_test_return_is_rejected(self):
        _, errors = check.validate_class(check.parse_class(class_bytes("()Lorg/json/JSONObject;")))
        self.assertIn("descriptor=()Lorg/json/JSONObject;", errors[0])

    def test_parameters_nonpublic_static_and_nonvoid_tests_rejected(self):
        for descriptor, access in [("(I)V", 1), ("()V", 2), ("()V", 9), ("()Z", 1)]:
            with self.subTest(descriptor=descriptor, access=access):
                self.assertTrue(check.validate_class(check.parse_class(class_bytes(descriptor, access)))[1])

    def test_runtime_class_requires_public_concrete_and_zeroarg_constructor(self):
        for options in [{"class_access": 0x0020}, {"class_access": 0x0421}, {"constructor": "(I)V"}]:
            with self.subTest(options=options):
                self.assertTrue(check.validate_class(check.parse_class(class_bytes(**options)))[1])

    def test_parameterized_and_inherited_base_classes_are_not_false_rejected(self):
        for options in [{"constructor": "(I)V"}, {"class_access": 0x0421}]:
            with self.subTest(options=options):
                parsed = check.parse_class(class_bytes(name="example/ParameterizedOrBaseTest", **options))
                self.assertEqual(check.validate_class(parsed), (1, []))

    def test_lifecycle_hooks_checked_alongside_tests(self):
        parsed = check.parse_class(class_bytes())
        test_method = parsed["methods"][1]
        for annotation, access, valid in [("Lorg/junit/Before;", 1, True),
                                           ("Lorg/junit/Before;", 9, False),
                                           ("Lorg/junit/BeforeClass;", 9, True),
                                           ("Lorg/junit/AfterClass;", 1, False)]:
            with self.subTest(annotation=annotation, access=access):
                parsed["methods"] = parsed["methods"][:2] + [dict(test_method, annotations={annotation}, access=access)]
                self.assertEqual(not check.validate_class(parsed)[1], valid)

    def test_directory_requires_compiled_runtime_test_and_respects_filter(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with self.assertRaisesRegex(ValueError, "No compiled"):
                check.check_directory(root)
            output = root / "debugAndroidTest/classes/Test.class"
            output.parent.mkdir(parents=True)
            output.write_bytes(class_bytes())
            self.assertEqual(check.check_directory(root, "androidtest"), (1, 1))
            with self.assertRaisesRegex(ValueError, "No compiled"):
                check.check_directory(root, "not-this-output")
            output.write_bytes(class_bytes(name="example/UnrelatedTest"))
            with self.assertRaisesRegex(ValueError, "Required compiled"):
                check.check_directory(root)

    def test_malformed_class_fails_closed(self):
        for data in [b"garbage", class_bytes()[:-1], class_bytes() + b"trailing"]:
            with self.subTest(data=data[:8]), self.assertRaises(ValueError):
                check.parse_class(data)


if __name__ == "__main__":
    unittest.main()
