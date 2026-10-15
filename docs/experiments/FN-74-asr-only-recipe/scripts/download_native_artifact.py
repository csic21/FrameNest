#!/usr/bin/env python3
"""Read the locked same-repository Actions artifact, without forwarding API credentials to storage."""
import argparse,json,os,shutil,urllib.error,urllib.parse,urllib.request
from pathlib import Path
from inputs import ROOT,digest
LOCK=json.loads((ROOT/'native-artifact-lock.json').read_text())
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,req,fp,code,msg,headers,newurl):return None

def download(out,report):
    if out.exists():raise ValueError('Refusing existing artifact download')
    if os.environ.get('GITHUB_REPOSITORY')!=LOCK['repository']:raise ValueError('Artifact reuse is restricted to same repository')
    token=os.environ.get('GITHUB_TOKEN')
    if not token:raise ValueError('Read-only Actions token missing')
    opener=urllib.request.build_opener(NoRedirect)
    def api(path):
        request=urllib.request.Request('https://api.github.com/repos/'+LOCK['repository']+path,headers={'Authorization':'Bearer '+token,'Accept':'application/vnd.github+json','X-GitHub-Api-Version':'2022-11-28','User-Agent':'FrameNest-AAR-recipe/1'})
        try:return opener.open(request,timeout=180)
        except urllib.error.HTTPError as e:
            if e.code==302:return e
            raise ValueError('Official artifact API failed with HTTP '+str(e.code)) from None
    with api('/actions/runs/'+str(LOCK['run_id'])) as response:run=json.load(response)
    if run['id']!=LOCK['run_id'] or run['head_sha']!=LOCK['commit'] or run['status']!='completed' or run['conclusion']!='success':raise ValueError('Locked native workflow run identity/status mismatch')
    with api('/actions/artifacts/'+str(LOCK['artifact_id'])) as response:meta=json.load(response)
    if meta['id']!=LOCK['artifact_id'] or meta['expired'] or meta['workflow_run']['id']!=LOCK['run_id'] or meta['workflow_run']['head_sha']!=LOCK['commit']:raise ValueError('Locked artifact identity mismatch or expired')
    if meta.get('digest') and meta['digest']!='sha256:'+LOCK['zip_sha256']:raise ValueError('Server artifact digest differs from reviewed lock')
    with api('/actions/artifacts/'+str(LOCK['artifact_id'])+'/zip') as response:
        if response.code!=302:raise ValueError('Expected separate artifact storage redirect')
        location=response.headers.get('Location','')
    parsed=urllib.parse.urlparse(location)
    if parsed.scheme!='https' or parsed.username or parsed.password or not parsed.hostname or not (parsed.hostname.endswith('.blob.core.windows.net') or parsed.hostname.endswith('.githubusercontent.com')):raise ValueError('Unexpected artifact storage origin; review required')
    out.parent.mkdir(parents=True,exist_ok=True)
    # New request, no Authorization header. Never print the signed storage URL.
    try:
        with urllib.request.urlopen(urllib.request.Request(location,headers={'User-Agent':'FrameNest-AAR-recipe/1'}),timeout=180) as source,out.open('xb') as target:shutil.copyfileobj(source,target)
    except urllib.error.URLError:raise ValueError('Artifact storage download failed; signed URL withheld') from None
    if digest(out)!=LOCK['zip_sha256']:raise ValueError('Downloaded native ZIP digest mismatch')
    evidence={'lock':LOCK,'artifact':{k:meta.get(k) for k in ['id','name','size_in_bytes','digest','expired','created_at','expires_at','workflow_run']},'run':{k:run.get(k) for k in ['id','head_sha','status','conclusion','html_url']},'download_sha256':digest(out)}
    report.parent.mkdir(parents=True,exist_ok=True);report.write_text(json.dumps(evidence,indent=2)+'\n');print('Verified locked native artifact:',LOCK['artifact_id'])
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--out',type=Path,required=True);p.add_argument('--report',type=Path,required=True);a=p.parse_args();download(a.out,a.report)
