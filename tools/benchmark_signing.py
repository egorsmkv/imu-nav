#!/usr/bin/env python3
"""Sign benchmark artifacts with a durable private key, or clearly label unsigned validation builds.

Keystore bytes and passwords come only from the environment. Temporary key files are never
placed in an artifact directory, and subprocess failures never echo secret-bearing commands.
"""
import argparse
import base64
import binascii
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile


KEY_VARIABLES = ('IMUNAV_BENCHMARK_KEYSTORE_BASE64', 'IMUNAV_BENCHMARK_STORE_PASSWORD',
                 'IMUNAV_BENCHMARK_KEY_ALIAS', 'IMUNAV_BENCHMARK_KEY_PASSWORD')
FINGERPRINT_VARIABLE = 'IMUNAV_BENCHMARK_CERT_SHA256'


def fingerprint(value):
    """Accept public SHA-256 fingerprints with or without keytool's colon separators."""
    normalized = value.replace(':', '').strip().lower()
    if not re.fullmatch(r'[0-9a-f]{64}', normalized):
        raise ValueError('Expected a complete SHA-256 certificate fingerprint')
    return normalized


def run_signer(arguments, env):
    """Keep keystore/parser diagnostics and CalledProcessError command strings out of CI logs."""
    result = subprocess.run(arguments, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            text=True, check=False, timeout=120)
    if result.returncode:
        raise ValueError('APK signing or signature verification failed; check the key, passwords and APK')
    return result.stdout


def verify(apksigner, apk, env, expected=None):
    """Require a valid signature and exactly one signer matching the pinned identity when supplied."""
    output = run_signer([str(apksigner), 'verify', '--verbose', '--print-certs', str(apk)], env)
    digests = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)\s*$', output, re.M)
    if len(digests) != 1:
        raise ValueError('Benchmark APK must have exactly one verified signing certificate')
    actual = fingerprint(digests[0])
    if expected is not None and actual != fingerprint(expected):
        raise ValueError('Benchmark certificate differs from the configured SHA-256 fingerprint')
    return actual


def sign(apk, output_dir, apksigner, allow_unsigned=False, env=None, temporary_root=None):
    """Restore the same key independently on each runner; never silently choose a fresh debug key."""
    env = dict(os.environ if env is None else env)
    apk, output_dir = Path(apk), Path(output_dir)
    if not apk.is_file() or not apk.name.endswith('-unsigned.apk'):
        raise ValueError('Expected an existing *-unsigned.apk built with -PunsignedBenchmark')
    if output_dir.resolve() == apk.parent.resolve():
        raise ValueError('Distribution output must be separate from the unsigned build directory')
    if output_dir.exists() and any(output_dir.iterdir()):
        raise ValueError('Distribution output must be empty to avoid mixing signing identities')
    supplied = [bool(env.get(name)) for name in KEY_VARIABLES]
    expected = env.get(FINGERPRINT_VARIABLE) or None
    if any(supplied) and not all(supplied):
        raise ValueError('Benchmark signing requires all four IMUNAV_BENCHMARK key settings')
    if expected:
        fingerprint(expected)
    if not all(supplied):
        if not allow_unsigned:
            raise ValueError('No durable benchmark signing key configured')
        output_dir.mkdir(parents=True, exist_ok=True)
        output = output_dir / apk.name
        shutil.copyfile(apk, output)
        report = {'mode': 'unsigned', 'apk': output.name, 'certificate_sha256': None,
                  'notice': 'Not installable. Configure a durable benchmark key or sign locally before installing.'}
    else:
        try:
            key = base64.b64decode(env[KEY_VARIABLES[0]], validate=True)
        except (ValueError, binascii.Error) as error:
            raise ValueError('Benchmark keystore is not valid base64') from error
        if not key:
            raise ValueError('Benchmark keystore is empty')
        with tempfile.TemporaryDirectory(prefix='imunav-benchmark-signing-', dir=temporary_root) as directory:
            private = Path(directory)
            keystore = private / 'benchmark.keystore'
            # TemporaryDirectory is owner-only; explicitly protect the file as well.
            with keystore.open('xb') as stream:
                os.chmod(keystore, 0o600)
                stream.write(key)
            staged = private / apk.name.replace('-unsigned.apk', '.apk')
            run_signer([str(apksigner), 'sign', '--ks', str(keystore),
                        '--ks-key-alias', env[KEY_VARIABLES[2]],
                        '--ks-pass', 'env:' + KEY_VARIABLES[1], '--key-pass', 'env:' + KEY_VARIABLES[3],
                        '--v4-signing-enabled', 'false', '--out', str(staged), str(apk)], env)
            actual = verify(apksigner, staged, env, expected)
            output_dir.mkdir(parents=True, exist_ok=True)
            output = output_dir / staged.name
            shutil.copyfile(staged, output)
        report = {'mode': 'signed', 'apk': output.name, 'certificate_sha256': actual}
    digest = hashlib.sha256()
    with output.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    report['apk_sha256'] = digest.hexdigest()
    (output_dir / 'benchmark-signing.json').write_text(json.dumps(report, indent=2) + '\n')
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--apksigner', type=Path, required=True)
    parser.add_argument('--allow-unsigned', action='store_true')
    args = parser.parse_args()
    try:
        report = sign(args.apk, args.output_dir, args.apksigner, args.allow_unsigned,
                      temporary_root=os.environ.get('RUNNER_TEMP'))
    except (ValueError, OSError, subprocess.TimeoutExpired) as error:
        # Do not print subprocess exceptions: they can contain key aliases or signing diagnostics.
        message = str(error) if isinstance(error, ValueError) else 'Benchmark signing tools could not complete'
        raise SystemExit(message) from None
    print(json.dumps(report))
    if os.environ.get('GITHUB_OUTPUT'):
        with Path(os.environ['GITHUB_OUTPUT']).open('a') as stream:
            stream.write(f"mode={report['mode']}\n")
    if report['mode'] == 'unsigned' and os.environ.get('GITHUB_STEP_SUMMARY'):
        with Path(os.environ['GITHUB_STEP_SUMMARY']).open('a') as stream:
            stream.write('Benchmark APK is **unsigned and not installable**: no private benchmark signing key is configured. '
                         'See docs/benchmark_signing.md for the one-time setup.\n')


if __name__ == '__main__':
    main()
