#!/usr/bin/env python3
"""Exercise actual Android update rules with tiny APKs and the benchmark distribution signer.

Uses only a dedicated, synthetic package on a disposable emulator. No navigation installation,
user trips or production keys are touched. Temporary fixture keys are never uploaded.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile

import benchmark_signing


PACKAGE = 'org.imunav.benchmarkupdatetest'


def run(arguments, env=None, check=True):
    result = subprocess.run([str(argument) for argument in arguments], env=env, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False, timeout=120)
    if check and result.returncode:
        raise RuntimeError('Benchmark update fixture command failed: ' + result.stdout)
    return result


def fixture_key(directory):
    """Generate a disposable identity; the real distribution path only restores existing keys."""
    password = secrets.token_hex(16)
    env = dict(os.environ, IMUNAV_BENCHMARK_STORE_PASSWORD=password,
               IMUNAV_BENCHMARK_KEY_PASSWORD=password, IMUNAV_BENCHMARK_KEY_ALIAS='fixture')
    env.pop(benchmark_signing.FINGERPRINT_VARIABLE, None)
    keystore = directory / 'fixture.keystore'
    run(['keytool', '-genkeypair', '-keystore', keystore, '-storetype', 'JKS', '-alias', 'fixture',
         '-storepass:env', benchmark_signing.KEY_VARIABLES[1], '-keypass:env', benchmark_signing.KEY_VARIABLES[3],
         '-keyalg', 'RSA', '-keysize', '2048', '-validity', '1', '-dname', 'CN=ImuNav Update Fixture'], env)
    env[benchmark_signing.KEY_VARIABLES[0]] = base64.b64encode(keystore.read_bytes()).decode('ascii')
    return env


def fixture_apk(directory, sdk, version):
    """Build an ABI-independent, debuggable fixture so run-as can check its own private sentinel."""
    manifest = directory / 'AndroidManifest.xml'
    manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
        package="{PACKAGE}" android:versionCode="{version}" android:versionName="fixture-{version}">
        <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36" />
        <application android:hasCode="false" android:debuggable="true" android:label="Update fixture" />
    </manifest>''')
    tools = sdk / 'build-tools/36.0.0'
    unaligned, apk = directory / 'unaligned.apk', directory / f'fixture-{version}-unsigned.apk'
    run([tools / 'aapt2', 'link', '-o', unaligned, '-I', sdk / 'platforms/android-36/android.jar', '--manifest', manifest])
    run([tools / 'zipalign', '-P', '16', '-f', '4', unaligned, apk])
    return apk


def exercise(sdk, evidence):
    """Prove mismatched signers fail, while independent restores of one identity preserve app data."""
    sdk, evidence = Path(sdk), Path(evidence)
    evidence.mkdir(parents=True, exist_ok=True)
    adb, apksigner = sdk / 'platform-tools/adb', sdk / 'build-tools/36.0.0/apksigner'
    with tempfile.TemporaryDirectory(prefix='imunav-update-fixtures-') as temporary:
        root = Path(temporary)
        first_dir, next_dir, other_dir = root / 'first', root / 'next', root / 'other'
        for directory in (first_dir, next_dir, other_dir):
            directory.mkdir()
        key = fixture_key(first_dir)
        other_key = fixture_key(other_dir)
        first = fixture_apk(first_dir, sdk, 1)
        newer = fixture_apk(next_dir, sdk, 2)
        original = benchmark_signing.sign(first, root / 'signed-first', apksigner, env=key)
        replacement = benchmark_signing.sign(newer, root / 'signed-next', apksigner, env=key)
        incompatible = benchmark_signing.sign(newer, root / 'signed-other', apksigner, env=other_key)
        if original['certificate_sha256'] != replacement['certificate_sha256']:
            raise RuntimeError('Restoring the same fixture key changed the signing certificate')
        if original['certificate_sha256'] == incompatible['certificate_sha256']:
            raise RuntimeError('Independent fixture keys unexpectedly have the same certificate')
        transcript = []
        try:
            transcript.append(run([adb, 'install', str(root / 'signed-first' / original['apk'])]).stdout)
            run([adb, 'shell', 'run-as', PACKAGE, 'mkdir', '-p', 'files'])
            # Synthetic sentinel belongs exclusively to the disposable fixture package.
            run([adb, 'shell', 'run-as', PACKAGE, 'sh', '-c', "'echo keep-this-fixture-data > files/sentinel'"])
            rejected = run([adb, 'install', '-r', str(root / 'signed-other' / incompatible['apk'])], check=False)
            transcript.append(rejected.stdout)
            if rejected.returncode == 0 or 'INSTALL_FAILED_UPDATE_INCOMPATIBLE' not in rejected.stdout:
                raise RuntimeError('Android did not reject an update signed by a different key')
            update = root / 'signed-next' / replacement['apk']
            # First an actual version update, then reinstall the same newer APK.
            for _ in range(2):
                transcript.append(run([adb, 'install', '-r', update]).stdout)
                sentinel = run([adb, 'shell', 'run-as', PACKAGE, 'cat', 'files/sentinel']).stdout.strip()
                if sentinel != 'keep-this-fixture-data':
                    raise RuntimeError('APK replacement lost the existing fixture data')
            (evidence / 'result.json').write_text(json.dumps({
                'wrong_key_rejected': True, 'same_key_update_and_reinstall_preserved_data': True,
                'original': original, 'replacement': replacement, 'incompatible': incompatible,
            }, indent=2) + '\n')
        finally:
            (evidence / 'adb.txt').write_text('\n'.join(transcript))
            # Remove only our synthetic package; never uninstall a navigation app.
            run([adb, 'uninstall', PACKAGE], check=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    exercise(args.sdk, args.output)


if __name__ == '__main__':
    main()
