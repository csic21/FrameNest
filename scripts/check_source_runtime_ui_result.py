#!/usr/bin/env python3
"""Write a separate phone-sized notice-UI result; never merge it into ASR metrics."""
import argparse
import json
from pathlib import Path
import re


def record(root, exit_code):
    destination = root / 'ui'
    destination.mkdir(exist_ok=True)
    output = (destination / 'instrumentation.txt').read_text() if (destination / 'instrumentation.txt').exists() else ''
    pair = json.loads((root / 'input/pair-verification.json').read_text())
    passed = exit_code == 0 and re.search(r'^OK \(7 tests\)', output, re.MULTILINE) is not None
    result = {'schema_version': 1, 'status': 'passed' if passed else 'failed',
              'test_class': 'com.framenest.ui.screens.SourceAsrLicensesDialogTest',
              'tests_expected': 7, 'tests_passed': 7 if passed else None, 'instrumentation_exit_code': exit_code,
              'app_apk_sha256': pair['app']['sha256'], 'test_apk_sha256': pair['test']['sha256'],
              'source_runtime_identity': pair['profile']['runtime_identity'],
              'scope': 'Phone-sized API35 x86_64 AVD; notice navigation/error/cancellation and actual pinned asset reads',
              'native_runtime_measured_by_this_ui_class': False, 'tablet_tested': False,
              'asr_accuracy_accepted': False, 'legal_clearance': False}
    (destination / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('results', type=Path)
    parser.add_argument('exit_code', type=int)
    args = parser.parse_args()
    record(args.results, args.exit_code)


if __name__ == '__main__':
    main()
