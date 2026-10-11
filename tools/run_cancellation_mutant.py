#!/usr/bin/env python3
"""Prove the socket-cancellation assertion fails without the cancel hook; restore source."""
from pathlib import Path
import subprocess, json, shutil, xml.etree.ElementTree as E
root = Path(__file__).resolve().parents[1]
out = root / 'evidence/2026-10-05/cancellation-mutant'
out.mkdir(parents=True, exist_ok=True)
source = root / 'protocol/src/main/kotlin/staff/android/network/HttpTransport.kt'
original = source.read_text()
needle = 'continuation.invokeOnCancellation { call.cancel() }'
assert original.count(needle) == 1
mutated = original.replace(needle, '// Deliberate defect: the socket outlives cancellation.')
(out / 'HttpTransport-mutated.kt').write_text(mutated)
try:
    source.write_text(mutated)
    command = ['./gradlew', ':server:test', '--tests', '*cancel while reading partial body*', '--rerun-tasks', '--no-build-cache']
    with (out / 'run.log').open('w') as stream:
        result = subprocess.run(command, cwd=root, stdout=stream, stderr=subprocess.STDOUT)
    files = list((root/'server/build/test-results/test').glob('TEST-*.xml'))
    cases = [case for file in files for case in E.parse(file).getroot().findall('testcase')]
    failures = [failure for case in cases for failure in case.findall('failure')]
    passed = (result.returncode != 0 and len(cases) == 1 and len(failures) == 1
        and 'expected: <true> but was: <false>' in failures[0].get('message',''))
    for file in files: shutil.copy2(file, out/file.name)
    (out/'results.json').write_text(json.dumps({'passed':passed,'tests':len(cases),'failures':len(failures),'exit_code':result.returncode,'witness':'actual Call.isCanceled must be true before releasing remote body'},indent=2)+'\n')
    if not passed: raise SystemExit('Mutation did not produce the intended assertion failure')
finally:
    source.write_text(original)
print('Expected cancellation witness verified; reference source restored')
