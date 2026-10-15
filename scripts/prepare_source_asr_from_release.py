#!/usr/bin/env python3
"""Explicit post-publication fetch of the immutable source AAR; no Actions fallback."""
import argparse
import hashlib
import os
from pathlib import Path
import tempfile
import urllib.error
import urllib.parse
import urllib.request

from prepare_source_asr import DESTINATION, LOCK

RELEASE_URL = ('https://github.com/csic21/FrameNest/releases/download/v0.6.10-internal/'
               'sherpa-onnx-1.13.8-asr-no-tts.aar')


def validate_url(url):
    if url != RELEASE_URL:
        raise ValueError('Only the fixed reviewed release AAR URL is allowed')


class ReleaseRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        parsed = urllib.parse.urlparse(newurl)
        if (parsed.scheme != 'https' or parsed.hostname != 'release-assets.githubusercontent.com'
                or parsed.port not in (None, 443) or parsed.username or parsed.password or parsed.fragment):
            raise ValueError('Unexpected official release storage redirect')
        return super().redirect_request(request, fp, code, message, headers, newurl)


def download_release(destination=DESTINATION, url=RELEASE_URL):
    validate_url(url)
    if destination.exists() or destination.is_symlink():
        raise ValueError('Refusing to overwrite existing source AAR')
    destination.parent.mkdir(parents=True, exist_ok=True)
    opener = urllib.request.build_opener(ReleaseRedirect)
    # No authentication is sent. The URL is fixed; do not print storage URLs/errors.
    try:
        with opener.open(urllib.request.Request(url, headers={'User-Agent': 'FrameNest-source-ASR/1'}), timeout=180) as response:
            with tempfile.NamedTemporaryFile(dir=destination.parent, prefix='.source-aar-', delete=True) as staged:
                sha = hashlib.sha256(); size = 0
                for chunk in iter(lambda: response.read(1024 * 1024), b''):
                    size += len(chunk)
                    if size > LOCK['aar_size_bytes']:
                        raise ValueError('Release AAR exceeds immutable size')
                    sha.update(chunk); staged.write(chunk)
                if size != LOCK['aar_size_bytes'] or sha.hexdigest() != LOCK['aar_sha256']:
                    raise ValueError('Release AAR size/SHA mismatch')
                staged.flush()
                # Atomic and exclusive: cannot replace a file created while fetching.
                os.link(staged.name, destination)
    except urllib.error.URLError:
        raise ValueError('Fixed release AAR unavailable; no fallback, URL withheld') from None
    return {'source_url': RELEASE_URL, 'size_bytes': size, 'sha256': sha.hexdigest()}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args()
    result = download_release()
    print('Verified fixed release AAR:', result['sha256'])
