"""Offline protocol tests invoking the real Kotlin CLI once (no inference/downloads)."""
import base64
import json
from pathlib import Path
import subprocess
import unittest


class LocalAgreementCliTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        lines = []
        labels = {}

        def line(label, *columns):
            labels[label] = len(lines)
            lines.append("\t".join(str(value) for value in columns))

        def hypothesis(label, generation, samples, text, origin=0):
            line(label, "H", generation, origin, origin + samples, samples,
                 base64.b64encode(text.encode("utf-8")).decode("ascii"))

        cls.raw = " A😀, B!\n\t\"quoted\"\\tail"
        line("reset", "RESET", 1, 0, 16000)
        hypothesis("first", 1, 48000, cls.raw)
        hypothesis("duplicate", 1, 48000, "different")
        hypothesis("second", 1, 96000, cls.raw + " more")
        line("eof", "EOF", 1)
        line("eof_again", "EOF", 1)
        line("reset2", "RESET", 2, 0, 16000)
        line("bad_utf8", "H", 2, 0, 1, 1, "/w==")
        line("bad_base64", "H", 2, 0, 1, 1, "!!!!")
        line("bad_number", "H", 2, 0, 2**63, 1, "YQ==")
        line("bad_operation", "NOPE")
        hypothesis("empty", 2, 1, "")
        hypothesis("overlong", 2, 2, "日" * 4097)
        hypothesis("valid_after_error", 2, 2, "valid")
        line("eof2", "EOF", 2)
        line("reset3", "RESET", 3, 100, 16000)
        hypothesis("conflict_first", 3, 48000, "abc", origin=100)
        hypothesis("conflict_second", 3, 96000, "abcd", origin=100)
        hypothesis("conflict", 3, 144000, "xyz", origin=100)
        hypothesis("conflict_budget", 3, 192000, "latest", origin=100)
        line("conflict_eof", "EOF", 3)
        completed = subprocess.run(
            [str(Path(__file__).with_name("run-cli.sh"))],
            input="\n".join(lines) + "\n", text=True, encoding="utf-8",
            capture_output=True, timeout=120, check=True,
        )
        parsed = [json.loads(value) for value in completed.stdout.splitlines()]
        if len(parsed) != len(lines):
            raise AssertionError(f"Expected {len(lines)} output lines, got {len(parsed)}")
        cls.results = {label: parsed[index] for label, index in labels.items()}
        cls.all_results = parsed

    def test_json_round_trip_preserves_unicode_spaces_controls_and_quotes(self):
        e = self.results["first"]["evidence"]
        self.assertEqual(self.raw, e["latestHypothesis"])
        self.assertEqual(self.raw, e["unconfirmedTail"])
        self.assertEqual("", e["confirmedPrefix"])
        self.assertEqual(self.raw, self.results["second"]["evidence"]["confirmedPrefix"])

    def test_duplicate_does_not_mutate_state(self):
        self.assertEqual("DUPLICATE_OBSERVATION", self.results["duplicate"]["error"])
        self.assertEqual(self.results["first"]["evidence"], self.results["duplicate"]["evidence"])

    def test_invalid_protocol_is_explicit_and_atomic(self):
        for label in ("bad_utf8", "bad_base64", "bad_number", "bad_operation"):
            with self.subTest(label=label):
                result = self.results[label]
                self.assertEqual("INPUT_ERROR", result["status"])
                self.assertEqual("INVALID_TSV_OR_ENCODING", result["error"])
                self.assertEqual(self.results["reset2"]["evidence"], result["evidence"])

    def test_empty_text_keeps_its_trailing_tsv_column(self):
        self.assertEqual("TENTATIVE", self.results["empty"]["status"])
        self.assertEqual("", self.results["empty"]["evidence"]["latestHypothesis"])

    def test_overlong_text_is_component_rejection_without_truncation(self):
        self.assertEqual("REJECTED", self.results["overlong"]["status"])
        self.assertEqual("TEXT_TOO_LONG", self.results["overlong"]["error"])
        self.assertEqual(self.results["empty"]["evidence"], self.results["overlong"]["evidence"])
        self.assertEqual(2, self.results["valid_after_error"]["evidence"]["hypothesisCount"])

    def test_conflict_keeps_advancing_full_latest_through_budget_and_eof(self):
        self.assertEqual("CONFLICT", self.results["conflict"]["status"])
        budget = self.results["conflict_budget"]
        self.assertEqual("TERMINAL", budget["status"])
        e = budget["evidence"]
        self.assertTrue(e["conflictObserved"])
        self.assertEqual("abc", e["confirmedPrefix"])
        self.assertEqual("latest", e["latestHypothesis"])
        self.assertIsNone(e["unconfirmedTail"])
        self.assertEqual(["HYPOTHESIS_BUDGET", "SOURCE_BUDGET"], e["terminalReasons"])
        eof = self.results["conflict_eof"]["evidence"]
        self.assertEqual("latest", eof["latestHypothesis"])
        self.assertEqual(e["terminalReasons"] + ["EOF"], eof["terminalReasons"])

    def test_eof_is_idempotent(self):
        self.assertEqual(self.results["eof"]["evidence"], self.results["eof_again"]["evidence"])

    def test_reset_audit_contains_counts_without_transcripts(self):
        audit = self.results["reset2"]["resetAudit"]
        self.assertEqual(2, audit["discardedHypothesisCount"])
        self.assertNotIn(self.raw, json.dumps(audit, ensure_ascii=False))
        self.assertIsNone(self.results["reset2"]["evidence"]["latestHypothesis"])

    def test_line_numbers_and_no_final_authorization(self):
        self.assertEqual(list(range(1, len(self.all_results) + 1)),
                         [result["line"] for result in self.all_results])
        for result in self.all_results:
            self.assertFalse(result["evidence"]["finalTranslationOrCoverageAllowed"])


if __name__ == "__main__":
    unittest.main()
