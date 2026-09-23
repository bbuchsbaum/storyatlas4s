from pathlib import Path
import subprocess,json,hashlib
root=Path('/private/tmp/storyatlas4s-workspace-m1-20260922')
mutants=[('horizon-leak','intaglio/src/main/scala/storyatlas4s/intaglio/VoyageLowering.scala','Words(scene, visibleRecallText)','Words(scene, None)','intaglioJVM/testOnly *VoyageLoweringSuite','explicit horizon text never falls back'),('untimed-traversal','shell/src/main/scala/storyatlas4s/shell/WorkspaceController.scala','val units = workspace.inventory.units','val units = workspace.inventory.units.filter(u => workspace.timing(u.id) != storymodel4s.codec.WorkspaceTiming.Untimed)','shellJVM/testOnly *WorkspaceControllerSuite','canonical traversal reaches every inventory outcome'),('wrong-policy','shell/src/main/scala/storyatlas4s/shell/WorkspaceExport.scala','controller.state.policy,','workspace.policies.head.id,','shellJVM/testOnly *WorkspaceExportSuite','untouched provider subset: authored-b')]
results=[]
for name,path,old,new,task,witness in mutants:
 p=root/path; original=p.read_bytes(); text=original.decode(); assert text.count(old)==1,(name,text.count(old)); changed=text.replace(old,new)
 p.write_text(changed)
 try:
  rc=subprocess.call(['python3','/private/tmp/m1-atlas-gate.py','mutant-'+name,task])
  log=Path('/private/tmp/workspace-m1-evidence/atlas/mutant-'+name+'.log').read_text()
  killed=rc!=0 and witness in log and 'Compilation failed' not in log and ('==> X' in log or 'Failed:' in log or 'failed' in log)
  results.append(dict(name=name,path=path,before_sha256=hashlib.sha256(original).hexdigest(),mutant_sha256=hashlib.sha256(changed.encode()).hexdigest(),old=old,new=new,exit_code=rc,killed=killed,witness=witness))
  if not killed: raise RuntimeError('Mutant not validly killed: '+name)
 finally:p.write_bytes(original)
Path('/private/tmp/workspace-m1-evidence/atlas/source-mutants.json').write_text(json.dumps(results,indent=2)+'\n')
