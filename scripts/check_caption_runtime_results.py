#!/usr/bin/env python3
"""Check collection completeness and UI execution, never translation semantics."""
import argparse
from collections import Counter
import json
from pathlib import Path
import re
import struct
import zlib

from prepare_caption_runtime_pair import ASSETS, PROFILE, digest, require

PACKAGE = 'com.framenest.feature.listen_translate.'
UI_COUNTS = {'ListenTranslateOverlayTest': 20, 'ListenTranslateDiagnosticsControlsTest': 16, 'CaptionDiagnosisFixtureTest': 3}
HY_ONLY = ('highQualityChoiceIsExplicitAndShowsCostBeforeListening',
           'unavailableAndSourceOnlyChoicesCannotOptInButSavedChoiceCanBeCleared')


def instrumentation(text):
    """Read terminal per-test events; a successful adb exit alone is insufficient.

    AndroidX InstrumentationResultPrinter reports assumption failures as -4.
    https://github.com/android/android-test/blob/main/runner/android_junit_runner/
    java/androidx/test/internal/runner/listener/InstrumentationResultPrinter.java
    """
    records, current = [], {}
    for line in text.splitlines():
        match = re.fullmatch(r'INSTRUMENTATION_STATUS: (class|test|numtests)=(.*)', line)
        if match:
            current[match[1]] = match[2]
        match = re.fullmatch(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)', line)
        if match:
            code = int(match[1])
            if code != 1:
                require('class' in current and 'test' in current, 'Missing terminal test identity')
                records.append((current['class'], current['test'], code))
            current = {}
    require('INSTRUMENTATION_CODE: -1' in text and not re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED', text), 'Instrumentation did not finish normally')
    require(len({(c, t) for c, t, _ in records}) == len(records), 'Duplicate terminal test identity')
    return records


def check_png(path, expected_size):
    """Validate the non-interlaced RGB/RGBA PNG emitted by Bitmap.compress."""
    require(path.is_file() and 0 < path.stat().st_size <= 32 * 1024**2, 'Missing/empty/oversized UI screenshot: ' + path.name)
    data = path.read_bytes()
    require(data[:8] == b'\x89PNG\r\n\x1a\n', 'Invalid UI PNG signature')
    offset, chunks, compressed = 8, [], bytearray()
    while offset < len(data):
        require(offset + 12 <= len(data), 'Truncated UI PNG chunk')
        length, kind = struct.unpack_from('>I4s', data, offset)
        end = offset + 12 + length
        require(end <= len(data), 'Truncated UI PNG data')
        payload = data[offset + 8:end - 4]
        require(zlib.crc32(kind + payload) & 0xffffffff == struct.unpack_from('>I', data, end - 4)[0], 'UI PNG CRC mismatch')
        chunks.append(kind)
        if kind == b'IHDR':
            require(chunks == [b'IHDR'] and length == 13, 'Invalid UI PNG header')
            width, height, depth, color, compression, filtering, interlace = struct.unpack('>IIBBBBB', payload)
            require((width, height) == expected_size, 'UI PNG dimensions differ from recorded display')
            require(depth == 8 and color in (2, 6) and (compression, filtering, interlace) == (0, 0, 0), 'Unexpected UI PNG format')
            stride = 1 + width * (3 if color == 2 else 4)
        elif kind == b'IDAT':
            compressed.extend(payload)
        elif kind == b'IEND':
            require(length == 0 and end == len(data), 'Invalid UI PNG ending')
        offset = end
    require(chunks and chunks[0] == b'IHDR' and chunks[-1] == b'IEND' and b'IDAT' in chunks, 'Incomplete UI PNG')
    decoder = zlib.decompressobj()
    pixels = decoder.decompress(compressed, stride * height + 1)
    require(len(pixels) == stride * height and decoder.eof and not decoder.unused_data and not decoder.unconsumed_tail,
            'Invalid UI PNG pixel stream')
    require(all(pixels[i] <= 4 for i in range(0, len(pixels), stride)), 'Invalid UI PNG row filter')
    return {'path': path.name, 'sha256': digest(data), 'width': width, 'height': height}


def screenshot_display_size(directory, natural_size):
    # API35 LogicalDisplay.dumpLocked records base/override DisplayInfo. Use the
    # actual display-0 override and rotation, never infer rotation from the PNG.
    candidates = {}
    for kind, info in re.findall(r'^\s*m(Base|Override)DisplayInfo=(DisplayInfo\{[^\n]+)',
                                 (directory / 'display.txt').read_text(), re.MULTILINE):
        if re.search(r', displayId 0,', info):
            require(kind not in candidates, 'Ambiguous display-0 metadata')
            candidates[kind] = info
    info = candidates.get('Override', candidates.get('Base', ''))
    extent = re.search(r', real (\d+) x (\d+),', info)
    rotation = re.search(r', rotation ([0-3]),', info)
    require(extent is not None and rotation is not None, 'Missing actual display dimensions/rotation')
    actual = tuple(map(int, extent.groups()))
    expected = natural_size[::-1] if int(rotation[1]) % 2 else natural_size
    require(actual == expected, 'Actual display dimensions differ from configured viewport/rotation')
    return actual


def check_session(root, name):
    directory = root / name
    require((directory / 'exit-code.txt').read_text().strip() == '0', 'Session shell failed')
    events = instrumentation((directory / 'instrumentation.txt').read_text())
    if name == 'diagnosis':
        require(events == [(PACKAGE + 'CaptionDiagnosisTest', 'compareReferenceRawSpanAndProductionWindows', 0)], 'Diagnosis was skipped, failed, or repeated')
        return {'status': 'passed', 'tests_passed': 1, 'tests_skipped': 0}
    counts, skipped = Counter(), []
    for cls, test, code in events:
        require(cls.startswith(PACKAGE) and cls[len(PACKAGE):] in UI_COUNTS, 'Unexpected UI test class')
        counts[cls[len(PACKAGE):]] += 1
        expected_skip = cls == PACKAGE + 'ListenTranslateDiagnosticsControlsTest' and any(test.startswith(method + '[') for method in HY_ONLY)
        require((expected_skip and code == -4) or (not expected_skip and code == 0), 'Unexpected test failure or skip: ' + test)
        if expected_skip:
            skipped.append(test)
    require(dict(counts) == UI_COUNTS and len(skipped) == 4, 'Incomplete phone/tablet UI suite')
    size, density = ('1600x2560', '240') if name == 'tablet' else ('1080x1920', '480')
    def effective_display_value(path, label):
        values = dict(re.findall(r'(Physical|Override) ' + label + r': (\S+)', path.read_text()))
        return values.get('Override', values.get('Physical'))
    require(effective_display_value(directory / 'display-size.txt', 'size') == size
            and effective_display_value(directory / 'display-density.txt', 'density') == density,
            'Viewport evidence missing or mismatched')
    screenshot_size = screenshot_display_size(directory, tuple(map(int, size.split('x'))))
    screenshots = [check_png(directory / f'controls-{width}.png', screenshot_size) for width in (360, 840)]
    return {'status': 'passed', 'tests_passed': 35, 'tests_skipped': 4, 'skipped_hy_mt_only_tests': skipped,
            'viewport_pixels': size, 'density_dpi': int(density), 'physical_tablet_tested': False,
            'screenshots': screenshots}


def check_diagnosis(root):
    report_bytes = (root / 'diagnosis/caption-diagnosis.json').read_bytes()
    report = json.loads(report_bytes)
    pair = json.loads((root / 'input/pair-verification.json').read_text())
    require(pair['status'] == 'retained-pair-verified', 'Input pair was not verified')
    require(report['schema_version'] == 1 and report['status'] == 'collected' and report['collection_complete'] is True, 'Diagnosis collection incomplete')
    require(report['app_apk_sha256'] == pair['lock']['app_apk_sha256']
            and report['test_apk_sha256'] == pair['lock']['test_apk_sha256'], 'Runtime APK hash mismatch')
    require(report['source_aar_sha256'] == PROFILE['aar_sha256'] and report['runtime_identity'] == PROFILE['runtime_identity']
            and report['native_version'].removeprefix('v') == '1.13.8' and report['api'] == 35 and report['abi'] == 'x86_64', 'Unexpected runtime identity/device')
    manifest_bytes = (ASSETS / 'manifest.json').read_bytes()
    manifest = json.loads(manifest_bytes)
    require(report['manifest_sha256'] == digest(manifest_bytes) and report['manifest'] == manifest, 'Runtime fixture mismatch')
    require(report['semantic_acceptance_passed'] is None and report['accuracy_accepted'] is False
            and report['hardware_performance_accepted'] is False and report['reference_is_verbatim_gold'] is False
            and report['reference_independently_listened'] is False, 'Unreviewed acceptance claim')
    require(report['verified_source_profile']['profile'] == PROFILE, 'Runtime source-profile mismatch')
    observed_mt = Counter()
    for key in ('reference_to_mt', 'raw_span_asr_to_mt'):
        rows = report[key]
        require(len(rows) == 4, 'Incomplete ' + key)
        for row, unit in zip(rows, manifest['units']):
            require(row['reference_unit_id'] == unit['id'] and row['span_start_ms'] == unit['start_ms']
                    and row['span_end_ms'] == unit['end_ms'] and row['semantic_acceptance_passed'] is None, 'Reference-span identity mismatch')
            require(row['mt_status'] in {'returned', 'empty_target', 'failed', 'skipped_empty_source'}, 'Missing real MT outcome')
            if row['mt_status'] in {'returned', 'empty_target'}:
                target = row.get('target_text')
                require(isinstance(target, str), 'Missing real MT target text')
                require(bool(target.strip()) == (row['mt_status'] == 'returned'),
                        'MT target text/status mismatch')
            elif row['mt_status'] == 'failed':
                require(isinstance(row.get('mt_failure_class'), str) and bool(row['mt_failure_class'].strip()),
                        'Missing real MT failure class')
            elif row['mt_status'] == 'skipped_empty_source':
                require(isinstance(row.get('input_text'), str) and not row['input_text'].strip()
                        and row.get('target_text') == '', 'Skipped MT requires empty source and target')
            if key == 'reference_to_mt':
                require(row['input_text'] == unit['text'] and row['mt_status'] != 'skipped_empty_source', 'Reference MT input mismatch')
            else:
                require(len(row['raw_tokens']) == len(row['token_start_seconds_relative_to_span']), 'Raw token/timestamp lengths differ')
            observed_mt[key + ':' + row['mt_status']] += 1
    rows = report['production_3s_to_mt']
    require(len(rows) == 16, 'Incomplete production windows')
    for i, row in enumerate(rows):
        require(row['window_id'] == f'p{i}' and row['window_start_ms'] == i * 3000
                and row['window_end_ms'] == min((i + 1) * 3000, 47720)
                and row['semantic_acceptance_passed'] is None, 'Production grid mismatch')
        require(isinstance(row['text_src'], str) and isinstance(row['text_tgt'], str)
                and type(row['translation_retryable_failure']) is bool, 'Missing production result')
        observed_mt['production_retryable_failure' if row['translation_retryable_failure'] else 'production_returned'] += 1
    return {'status': 'collected', 'report_sha256': digest(report_bytes), 'rows': {'reference': 4, 'raw_span': 4, 'production': 16},
            'mt_observations': dict(observed_mt), 'semantic_acceptance_passed': None,
            'scope': 'Completeness only. Retained real translations and failures require separate human review; no accuracy or speedup claim.'}


def record(root):
    result = {'schema_version': 1, 'status': 'failed', 'semantic_acceptance_passed': None, 'checks': {}}
    for name in ('phone', 'tablet', 'diagnosis', 'three_path_evidence'):
        try:
            result['checks'][name] = check_diagnosis(root) if name == 'three_path_evidence' else check_session(root, name)
        except Exception as error:
            result['checks'][name] = {'status': 'failed', 'failure_class': type(error).__name__, 'failure': str(error)}
    passed = all(check['status'] in {'passed', 'collected'} for check in result['checks'].values())
    result['status'] = 'evidence-collected' if passed else 'failed'
    (root / 'validation-summary.json').write_text(json.dumps(result, indent=2) + '\n')
    return passed


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('results', type=Path)
    args = parser.parse_args()
    raise SystemExit(0 if record(args.results) else 1)
