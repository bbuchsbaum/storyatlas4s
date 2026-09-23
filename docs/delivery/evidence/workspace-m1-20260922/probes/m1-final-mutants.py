from pathlib import Path
import subprocess,json,hashlib,os,re
r=Path('/private/tmp/storyatlas4s-workspace-m1-20260922');e=Path('/private/tmp/workspace-m1-evidence/atlas');f='/private/tmp/storymodel4s-workspace-m1-20260922/docs/refactor/evidence/workspace-m1-packet-20260922/fixtures';os.environ['PLAYWRIGHT_BROWSERS_PATH']='/private/tmp/storyatlas4s-m1-playwright-20260922';results=[]
def gate(name,*tasks):return subprocess.call(['python3','/private/tmp/m1-atlas-gate.py',name,*tasks])
p=r/'app/src/main/scala/storyatlas4s/app/VoyageView.scala';original=p.read_bytes();old='val ordered = scene.units.sortBy(_.ordinal)';new='val ordered = scene.units.filter(_.onset.nonEmpty).sortBy(_.ordinal)';assert original.decode().count(old)==1
try:
 changed=original.decode().replace(old,new);p.write_text(changed)
 assert gate('legacy-mutant-link','app/fastLinkJS','app/editionBundle')==0
 rc=subprocess.call(['python3','/Users/bbuchsbaum/.agents/skills/lean-logs/scripts/run_logged.py','--log',str(e/'legacy-mutant-browser.log'),'--timeout','60','--','node','app/smoke/legacy-workspace.cjs','target/edition/index.html',f,str(e/'legacy-mutant')],cwd=r)
 report=json.loads((e/'legacy-mutant/legacy-workspace-browser.json').read_text());assert rc==1 and len(report['checks'])==5 and 'Timeout' in report['failure'],report
 results.append(dict(name='legacy-timed-only',file=str(p.relative_to(r)),original_sha256=hashlib.sha256(original).hexdigest(),old=old,new=new,killed=True,exit_code=rc,witness='legacy first untimed canonical unit does not activate'))
finally:p.write_bytes(original)
p=r/'shell/src/main/scala/storyatlas4s/shell/WorkspaceImport.scala';original=p.read_bytes();pattern=r'StoryModelCodec.encode\(source.draft\) == StoryModelCodec.encode\(\s*controller.workspace.draft.model\s*\)';found=re.search(pattern,original.decode());assert found
try:
 p.write_text(re.sub(pattern,'true',original.decode(),count=1));rc=gate('attach-foreign-mutant','shellJVM/testOnly *WorkspaceImportSuite');log=(e/'attach-foreign-mutant.log').read_text();assert rc==1 and 'foreign models and unsupported source addresses refuse attachment' in log and 'Compilation failed' not in log
 results.append(dict(name='attach-foreign-model',file=str(p.relative_to(r)),original_sha256=hashlib.sha256(original).hexdigest(),old=found.group(),new='true',killed=True,exit_code=rc,witness='foreign models and unsupported source addresses refuse attachment'))
finally:p.write_bytes(original)
(e/'final-source-mutants.json').write_text(json.dumps(results,indent=2)+'\n')
