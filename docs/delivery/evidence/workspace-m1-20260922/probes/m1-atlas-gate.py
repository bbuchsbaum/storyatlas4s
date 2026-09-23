import subprocess,sys
base=['sbt','-batch','-J-Xms256m','-J-Xmx2G','-Dsbt.global.staging=/private/tmp/storymodel4s-m1-sbt-staging-20260922','-Dstoryatlas4s.storymodel4s.build=/private/tmp/storymodel4s-workspace-m1-20260922','-Dstorymodel4s.grakern.build=/private/tmp/storymodel4s-m1-grakern-20260922','-Dstoryatlas4s.intaglio.build=/private/tmp/storymodel4s-m1-intaglio-20260922']
cmd=[sys.executable,'/Users/bbuchsbaum/.agents/skills/lean-logs/scripts/run_logged.py','--log','/private/tmp/workspace-m1-evidence/atlas/'+sys.argv[1]+'.log','--timeout','1800','--']+base+sys.argv[2:]
sys.exit(subprocess.call(cmd,cwd='/private/tmp/storyatlas4s-workspace-m1-20260922'))
