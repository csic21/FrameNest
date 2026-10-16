"""Fail-closed, test-only 3s/6s plus full Emoji regression contract."""
from caption_diagnosis_schema import file_spec, integer, public_url, text


def validate_comparison(manifest):
    integer(manifest['schema_version'], 1, 1, 'comparison schema')
    if manifest['id'] != 'fn76-fixed-3s-6s-mcdc-emoji':
        raise ValueError('Unknown fixed comparison')
    if manifest['window_ms'] != [3000, 6000] or any(type(v) is not int for v in manifest['window_ms']):
        raise ValueError('Only the fixed 3000/6000 ms comparison is authorized')
    integer(manifest['context_padding_ms'], 750, 750, 'unchanged context')
    integer(manifest['timeout_ms'], 1200000, 1200000, 'bounded total runtime')
    emoji = manifest['emoji']
    if emoji['id'] != 'emoji':
        raise ValueError('Expected full Emoji regression')
    integer(emoji['duration_ms'], 113960, 113960, 'full Emoji extent')
    integer(emoji['original_media_start_ms'], 0, 0, 'full original start')
    expected = {
        'source_url': 'https://commons.wikimedia.org/wiki/File:Emoji.webm',
        'media_url': 'https://upload.wikimedia.org/wikipedia/commons/a/a4/Emoji.webm',
        'license_url': 'https://creativecommons.org/licenses/by-sa/4.0/',
    }
    for key, value in expected.items():
        if public_url(emoji[key]) != value:
            raise ValueError('Full Emoji public source changed')
    if emoji['license'] != 'CC-BY-SA-4.0' or emoji['author'] != 'Simpleshow Japan':
        raise ValueError('Full Emoji attribution changed')
    text(emoji['attribution'], 'attribution')
    if emoji['reference_kind'] != 'none_independent_reference_unavailable' or emoji['reference_is_verbatim_gold'] is not False:
        raise ValueError('Do not invent Emoji gold')
    expected_specs = {
        'media': ('emoji.webm', 9487128, 'ad3af093836cad823ef1793ff4d505139eed92dd7e7402de4c015a2ed50d5956'),
        'pcm': ('emoji.s16le', 3646720, 'e3e4e1de9395948bc3e03c0f88a93cb55bbbb0768280344bd7f4bd3f164700a6'),
    }
    for key, expected in expected_specs.items():
        spec = emoji[key]
        file_spec(spec, 10000000)
        if (spec['file'], spec['bytes'], spec['sha256']) != expected:
            raise ValueError('Full Emoji existing bytes must not be repinned')
    pcm = emoji['pcm']
    integer(pcm['sample_rate_hz'], 16000, 16000, 'sample rate')
    integer(pcm['channels'], 1, 1, 'channels')
    integer(pcm['samples'], 1823360, 1823360, 'full sample count')
    if pcm['encoding'] != 's16le':
        raise ValueError('Expected signed little-endian PCM16')
    return manifest
