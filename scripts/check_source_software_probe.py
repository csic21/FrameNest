#!/usr/bin/env python3
"""Fail-fast software-only evidence checks; never interpret TCG cost as product speed."""
import argparse
import json
from pathlib import Path

from check_source_runtime_results import require, summarize

MODE = {'execution_backend': 'android-emulator-software-tcg', 'acceleration': 'off',
        'timing_interpretation': 'All wall times are software-emulation cost only, never product speed or historical-stock speedup',
        'hardware_performance_accepted': False, 'accuracy_accepted': False,
        'historical_stock_timing_comparison_accepted': False}


def validate_session(root, index):
    pair = json.loads((root / 'input/pair-verification.json').read_text())
    require(pair['status'] == 'retained-pair-verified', 'Unverified retained pair')
    require((root / f'session-{index}/shell-status.txt').read_text().strip() == 'passed', 'Instrumentation failed')
    report = json.loads((root / f'session-{index}/result.json').read_text())
    require(report['schema_version'] == 1 and report['status'] == 'passed', 'Incomplete source report')
    require(report['runtime_actual_native'].removeprefix('v') == '1.13.8', 'Actual JNI version mismatch')
    require(report['runtime_build_identity'] == pair['profile']['runtime_identity'], 'Source identity mismatch')
    require(report['source_aar_sha256'] == pair['profile']['aar_sha256'], 'Source AAR mismatch')
    require(report['source_runtime_comparison'] is True and report['hy_mt_native_build_profile'] is False, 'Wrong profile')
    require(report['apk_sha256'] == pair['app']['sha256'] and report['test_apk_sha256'] == pair['test']['sha256'], 'Exact installed pair mismatch')
    require(report['fixture_manifest_sha256'] == pair['fixture_manifest_sha256'], 'Frozen input mismatch')
    require(report['api'] == 35 and report['abi'] == 'x86_64', 'Wrong target')
    require(report['windows_per_pass'] == 33 and len(report['runs']) == 198, 'Incomplete source window replay')
    require(report['production_float_input_bit_identical_to_raw'] is True
            and report['production_float_roundtrip_max_abs_error'] == 0, 'PCM roundtrip changed')
    require(report['raw_stream_counts_balanced'] is True and report['production_close_reload_result_equal'] is True, 'Lifecycle failed')
    counts = report['lifecycle']
    require(counts['raw_stream_created'] == counts['raw_stream_released'] == 99, 'Stream count mismatch')
    require(counts['raw_recognizer_created'] == counts['raw_recognizer_released'] == 1, 'Recognizer count mismatch')
    require(report['cancellation_probe']['native_decode_interrupted_proven'] is False, 'Unsupported cancellation claim')
    require(all(report[key] is False for key in ('gap_coverage_accepted', 'accuracy_accepted',
            'hardware_performance_accepted', 'translation_tested')), 'Unsupported acceptance claim')
    return {'status': 'passed', 'session': index, 'rows': 198, **MODE}


def record(root, mode, values):
    root.mkdir(parents=True, exist_ok=True)
    result = {'schema_version': 1, 'status': 'failed', **MODE}
    if mode == 'failed':
        result.update(failed_phase=values[0], exit_code=int(values[1]), phases_after_failure_attempted=False)
    elif mode == 'session':
        index = int(values[0])
        require(index in (1, 2), 'Invalid source session')
        result.update(validate_session(root, index))
        (root / f'session-{index}/software-verification.json').write_text(json.dumps(result, indent=2) + '\n')
        return result
    else:
        for index in (1, 2):
            validate_session(root, index)
        native = summarize(root)
        native.update(MODE)
        (root / 'source-session-summary.json').write_text(json.dumps(native, indent=2) + '\n')
        ui = json.loads((root / 'ui/result.json').read_text())
        require(ui['status'] == 'passed' and ui['tests_passed'] == 7, 'Notice UI suite failed')
        require((root / 'ui/shell-status.txt').read_text().strip() == 'passed', 'UI shell failed')
        result.update(status=native['status'], completed_source_sessions=2, ui_tests_passed=7)
    (root / 'software-probe.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('results', type=Path)
    parser.add_argument('mode', choices=('session', 'finish', 'failed'))
    parser.add_argument('values', nargs='*')
    args = parser.parse_args()
    result = record(args.results, args.mode, args.values)
    return 0 if result['status'] == 'passed' or args.mode == 'failed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
