#!/usr/bin/env python3
"""Validate source app session output repeatability; OS caches can remain warm."""
import argparse
import json
from pathlib import Path


def require(condition, message):
    if not condition:
        raise ValueError(message)


def summarize(root):
    result = {'schema_version': 1, 'status': 'failed', 'sessions': [],
              'session_scope': 'One AVD; two fresh app installs/processes; OS caches may remain warm',
              'historical_stock_timing_comparison_accepted': False, 'accuracy_accepted': False,
              'hardware_performance_accepted': False, 'arm64_tested': False, 'device_16k_accepted': False}
    outputs = []
    try:
        pair = json.loads((root / 'input/pair-verification.json').read_text())
        require(pair['status'] == 'retained-pair-verified', 'Unverified input pair')
        for index in (1, 2):
            require((root / f'session-{index}/shell-status.txt').read_text().strip() == 'passed', 'Session shell/instrumentation did not pass')
            report = json.loads((root / f'session-{index}/result.json').read_text())
            require(report['schema_version'] == 1 and report['status'] == 'passed', 'Session did not complete')
            require(report['runtime_actual_native'].removeprefix('v') == '1.13.8', 'Unexpected native version')
            require(report['source_runtime_comparison'] is True and report['hy_mt_native_build_profile'] is False, 'Source-only mode mismatch')
            require(report['runtime_build_identity'] == pair['profile']['runtime_identity'], 'Source identity mismatch')
            require(report['source_aar_sha256'] == pair['profile']['aar_sha256'], 'Source AAR mismatch')
            require(report['apk_sha256'] == pair['app']['sha256'] and report['test_apk_sha256'] == pair['test']['sha256'], 'Installed APK pair mismatch')
            require(report['fixture_manifest_sha256'] == pair['fixture_manifest_sha256'], 'Frozen input mismatch')
            require(report['api'] == 35 and report['abi'] == 'x86_64', 'Unexpected target')
            require(report['windows_per_pass'] == 33 and len(report['runs']) == 198, 'Incomplete main result rows')
            require(all(report[key] is False for key in ('gap_coverage_accepted', 'accuracy_accepted',
                    'hardware_performance_accepted', 'translation_tested')), 'Unsupported acceptance claim')
            require(report['production_float_input_bit_identical_to_raw'] is True
                    and report['production_float_roundtrip_max_abs_error'] == 0, 'PCM roundtrip mismatch')
            require(report['raw_stream_counts_balanced'] is True and report['production_close_reload_result_equal'] is True, 'Lifecycle failure')
            lifecycle = report['lifecycle']
            require(lifecycle['raw_stream_created'] == lifecycle['raw_stream_released'] == 99, 'Raw stream count mismatch')
            require(lifecycle['raw_recognizer_created'] == lifecycle['raw_recognizer_released'] == 1, 'Recognizer count mismatch')
            require(report['cancellation_probe']['native_decode_interrupted_proven'] is False, 'Unsupported native cancellation claim')
            output = [{key: value for key, value in row.items() if key != 'decode_ms'} for row in report['runs']]
            outputs.append(output)
            result['sessions'].append({'session': index, 'completed': True, 'rows': len(output),
                                       'lifecycle': lifecycle, 'cancellation_probe': report['cancellation_probe']})
        differences = [index for index, (a, b) in enumerate(zip(*outputs)) if a != b]
        result['different_output_row_indices'] = differences
        result['two_fresh_sessions_output_equal'] = not differences
        result['status'] = 'passed' if not differences else 'output-repeatability-difference'
    except (OSError, ValueError, KeyError, TypeError) as error:
        result['failure_class'] = type(error).__name__
        result['failure'] = str(error)
    (root / 'source-session-summary.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('results', type=Path)
    args = parser.parse_args()
    result = summarize(args.results)
    print(json.dumps(result, indent=2))
    return 0 if result['status'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
