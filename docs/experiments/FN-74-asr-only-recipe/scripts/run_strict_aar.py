#!/usr/bin/env python3
"""Use only a separately committed, digest-bound Gradle review to build the candidate."""
import argparse,json,subprocess
from pathlib import Path
from inputs import ROOT,digest

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ['project','sdk','host-provenance']:p.add_argument('--'+name,type=Path,required=True)
    a=p.parse_args();root=ROOT/'gradle-lock';approval=json.loads((root/'approved-lock.json').read_text())
    if approval.get('status')!='independently-reviewed':raise ValueError('Gradle review is not approved')
    lock=root/'verification-metadata.xml';review=root/'independent-review.json'
    if digest(lock)!=approval['verification_sha256'] or digest(review)!=approval['review_sha256']:raise ValueError('Committed review digest mismatch')
    subprocess.run(['python3',str(ROOT/'scripts/package_aar.py'),'build','--project',str(a.project),'--sdk',str(a.sdk),'--exploratory-host-provenance',str(a.host_provenance),'--verification-file',str(lock),'--verification-sha256',approval['verification_sha256'],'--review-file',str(review),'--review-sha256',approval['review_sha256']],check=True)
if __name__=='__main__':main()
