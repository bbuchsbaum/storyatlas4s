#!/usr/bin/env python3
"""Compiling falsifiers for feature rendering and preservation of derivation incompleteness.
Run from the repository root after the baseline passes. Supply the three exact dependency
clones, a replay bundle with discontinuous situation support, and a fresh output directory.
PLAYWRIGHT_CHROMIUM_EXECUTABLE may name a task-owned browser from the project's pin.
"""
import argparse, hashlib, json, pathlib, re, subprocess
p = argparse.ArgumentParser()
for arg in ('model', 'intaglio', 'grakern', 'bundle', 'out'):
    p.add_argument('--' + arg, required=True)
a = p.parse_args()
out = pathlib.Path(a.out).resolve(); out.mkdir(parents=True, exist_ok=False)
head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
base = ['sbt', '-batch', '-Dstoryatlas4s.storymodel4s.build=' + a.model,
        '-Dstoryatlas4s.intaglio.build=' + a.intaglio, '-Dstorymodel4s.grakern.build=' + a.grakern]
lowering = pathlib.Path('intaglio/src/main/scala/storyatlas4s/intaglio/AtlasLowering.scala')
reader = pathlib.Path('cli/src/main/scala/storyatlas4s/cli/ModelInput.scala')
mutants = [
    ('drop-feature-rail', lowering,
     'val marks = scene.marks.collect { case f: VisualPrimitive.Feature => f }',
     'val marks = scene.marks.collect { case f: VisualPrimitive.Feature => f }.take(0)', False),
    ('hull-support', lowering, 'v.support.spans.toVector.traverse { span =>',
     'Vector(v.support.minSpan).traverse { span =>', True),
    ('drop-missing-mask', lowering, 'if missing.isDefined && !missing.contains(',
     'if false && missing.isDefined && !missing.contains(', True),
    ('omit-derivation-gaps', reader, ' || draftModel.gaps.nonEmpty', '', False),
    ('omit-abstentions', reader, ' || draftModel.abstentions.nonEmpty', '', False),
]
expected_tests = {
    'drop-feature-rail': 'features.json and its sidecars beside the model are bound, verified and materialized',
    'omit-derivation-gaps': 'a derivation.json beside the model is read as its record, bound to that model',
    'omit-abstentions': 'an abstention retains the draft view independently of gaps and structural validation',
}
baseline_cmd = base + ['cli/testOnly *ModelInputSuite',
                       f'cli/run edition --model {a.bundle}/storymodel.json --out {out / "baseline"}']
with (out / 'baseline.log').open('w') as f:
    f.write('HEAD ' + head + '\nCOMMAND ' + json.dumps(baseline_cmd) + '\n'); f.flush()
    result = subprocess.run(baseline_cmd, stdout=f, stderr=subprocess.STDOUT)
    f.write('COMMAND_EXIT ' + str(result.returncode) + '\n')
assert result.returncode == 0, 'unmutated baseline must pass before mutation'
with (out / 'baseline.log').open('a') as f:
    cmd = ['node', 'e2e/static/features.cjs', a.bundle, str(out / 'baseline')]
    f.write('COMMAND ' + json.dumps(cmd) + '\n'); f.flush()
    result = subprocess.run(cmd, stdout=f, stderr=subprocess.STDOUT)
    f.write('COMMAND_EXIT ' + str(result.returncode) + '\n')
assert result.returncode == 0, 'unmutated browser court must pass before mutation'
receipts = []
for name, file, before, after, browser in mutants:
    original = file.read_bytes(); source = original.decode()
    assert source.count(before) == 1, (name, 'mutation anchor changed')
    mutated = source.replace(before, after).encode()
    commands = []
    try:
        file.write_bytes(mutated)
        cmd = base + ([f'cli/run edition --model {a.bundle}/storymodel.json --out {out / name}']
                      if browser else ['cli/testOnly *ModelInputSuite'])
        log = out / (name + '.log')
        with log.open('w') as f:
            f.write('HEAD ' + head + '\nCOMMAND ' + json.dumps(cmd) + '\n'); f.flush()
            result = subprocess.run(cmd, stdout=f, stderr=subprocess.STDOUT)
            commands.append({'command': cmd, 'exit': result.returncode})
        content = log.read_text()
        assert 'Compilation failed' not in content, (name, 'compile failure is not a kill')
        if browser:
            assert result.returncode == 0, (name, 'generation failed before browser court')
            cmd = ['node', 'e2e/static/features.cjs', a.bundle, str(out / name)]
            with log.open('a') as f:
                f.write('COMMAND ' + json.dumps(cmd) + '\n'); f.flush()
                result = subprocess.run(cmd, stdout=f, stderr=subprocess.STDOUT)
                commands.append({'command': cmd, 'exit': result.returncode})
            content = log.read_text()
            expected = 'support was hulled or dropped' if name == 'hull-support' else 'missingness crosses absent'
            killed = result.returncode != 0 and expected in content
        else:
            totals = re.findall(r'Total (\d+), Failed (\d+), Errors (\d+), Passed (\d+)', content)
            killed = (result.returncode != 0 and any(int(f) > 0 and int(e) == 0 and int(p) > 0 for _, f, e, p in totals)
                      and re.search(r'==> X [^\n]*' + re.escape(expected_tests[name]), content) is not None)
        receipt = {'name': name, 'head': head, 'file': str(file), 'commands': commands,
                   'original_sha256': hashlib.sha256(original).hexdigest(),
                   'mutant_sha256': hashlib.sha256(mutated).hexdigest(), 'killed': killed}
        receipts.append(receipt)
        (out / 'receipts.json').write_text(json.dumps(receipts, indent=2) + '\n')
        assert killed, (name, 'mutant survived or failed outside its court')
        print(name + ': killed', flush=True)
    finally:
        file.write_bytes(original)
