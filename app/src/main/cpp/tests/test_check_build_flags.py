import unittest
from check_build_flags import TARGETS, audit


def commands(flags="-g -O0 -O3"):
    return [{"file": "source.cpp", "command": f"c++ {flags} -o CMakeFiles/{target}.dir/source.cpp.o -c source.cpp"}
            for target in sorted(TARGETS)]


class BuildFlagAuditTest(unittest.TestCase):
    def test_last_optimization_wins_with_debug_symbols_and_assertions_retained(self):
        self.assertEqual(audit(commands(), debug=True)["effective_optimization"], "-O3")

    def test_later_o0_is_rejected(self):
        with self.assertRaises(AssertionError):
            audit(commands("-g -O3 -O0"))

    def test_optimizing_only_bridge_is_rejected(self):
        entries = commands("-g -O0")
        entries[0]["command"] += " -O3"
        with self.assertRaises(AssertionError):
            audit(entries)

    def test_missing_kernel_target_is_rejected(self):
        with self.assertRaises(AssertionError):
            audit(commands()[:1])

    def test_nonportable_isa_is_rejected(self):
        for flag in ["-march=native", "-mcpu=native", "-mavx2", "-mamx-tile", "-mf16c", "-march=armv8.2-a+dotprod"]:
            with self.subTest(flag=flag), self.assertRaises(AssertionError):
                audit(commands(f"-O3 {flag}"))

    def test_abi_baseline_and_sanitizer_are_preserved(self):
        result = audit(commands("-g -O3 -march=x86-64-v2 -msse4.2 -fsanitize=address"), debug=True,
                       require_flags=["-fsanitize=address"])
        self.assertTrue(result["portable_isa_check_passed"])

    def test_lost_debug_symbols_assertions_or_sanitizer_is_rejected(self):
        for flags in ["-O3", "-g -O3 -g0", "-g -O3 -DNDEBUG", "-g -O3 -DNDEBUG=0"]:
            with self.subTest(flags=flags), self.assertRaises(AssertionError):
                audit(commands(flags), debug=True)
        with self.assertRaises(AssertionError):
            audit(commands(), require_flags=["-fsanitize=address"])


if __name__ == "__main__":
    unittest.main()
