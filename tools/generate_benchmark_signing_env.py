#!/usr/bin/env python3
"""Prepare durable benchmark signing credentials without printing or uploading secrets.

Creates a private bundle containing a JKS keystore, Bash/PowerShell environment exports and
the GitHub Actions secret/variable values. Existing identities can be reused explicitly.
"""
import argparse
import base64
import csv
from contextlib import contextmanager
import getpass
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import shutil
import subprocess
import sys
import tempfile

from benchmark_signing import FINGERPRINT_VARIABLE, KEY_VARIABLES, fingerprint


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT = ROOT / 'keystore' / 'benchmark-signing'
DEFAULT_ALIAS = 'imunav-benchmark'


@contextmanager
def private_bundle(output, env):
    """Reserve a new directory and remove it only when failure leaves it completely empty."""
    output.mkdir(mode=0o700)
    try:
        protect_directory(output, env)
        yield
    finally:
        try:
            output.rmdir()  # Nonrecursive: a published identity or any other file is always retained.
        except OSError:
            pass


def run_private(arguments, env):
    """Capture diagnostics: keytool errors and exception strings must not reach the console."""
    result = subprocess.run([str(value) for value in arguments], env=env, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, text=True, check=False, timeout=120)
    if result.returncode:
        raise ValueError('Signing-key preparation failed; check keytool, alias and passwords')
    return result.stdout


def protect_directory(directory, env):
    """Restrict access before writing any secret; Windows requires an ACL rather than chmod."""
    if os.name == 'nt':
        identity = run_private(['whoami', '/user', '/fo', 'csv', '/nh'], env)
        rows = list(csv.reader(identity.splitlines()))
        if len(rows) != 1 or len(rows[0]) != 2 or not re.fullmatch(r'S-1-\d+(?:-\d+)+', rows[0][1]):
            raise ValueError('Could not determine the current Windows account for a private ACL')
        run_private(['icacls', directory, '/inheritance:r', '/grant:r', f'*{rows[0][1]}:(OI)(CI)F'], env)
    else:
        directory.chmod(0o700)


def private_file(path, contents):
    """Exclusive creation prevents replacement, and files remain owner-only on POSIX hosts."""
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'wb') as stream:
        stream.write(contents)


def locate_keytool(env):
    """Prefer the selected JDK so Windows does not accidentally use a JRE-only PATH entry."""
    if env.get('JAVA_HOME'):
        candidate = Path(env['JAVA_HOME']) / 'bin' / ('keytool.exe' if os.name == 'nt' else 'keytool')
        if candidate.is_file():
            return str(candidate)
    candidate = shutil.which('keytool', path=env.get('PATH'))
    if candidate is None:
        raise ValueError('JDK keytool is required; set JAVA_HOME or use --keytool')
    return candidate


def password(env, variable, prompt, default=None):
    """Existing passwords come from the environment or a masked terminal, never CLI arguments."""
    if env.get(variable):
        return env[variable]
    if not sys.stdin.isatty():
        raise ValueError('Reusing a key requires environment passwords or an interactive terminal')
    value = getpass.getpass(prompt) or default
    if not value:
        raise ValueError('An existing keystore password cannot be empty')
    return value


def write_exports(directory, values):
    """Quote Bash literals and encode PowerShell values so Unicode smart quotes cannot become code."""
    shell = '# Contains private signing credentials. Do not share or commit.\n'
    shell += ''.join(f'export {name}={shlex.quote(value)}\n' for name, value in values.items())
    powershell = '# Contains private signing credentials. Do not share or commit.\n'
    for name, value in values.items():
        encoded = base64.b64encode(value.encode('utf-8')).decode('ascii')
        powershell += f"$env:{name} = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String('{encoded}'))\n"
    private_file(directory / 'env.sh', shell.encode('utf-8'))
    private_file(directory / 'env.ps1', powershell.encode('utf-8-sig'))
    github = {
        'secrets': {name.removeprefix('IMUNAV_'): values[name] for name in KEY_VARIABLES},
        'variables': {'BENCHMARK_CERT_SHA256': values[FINGERPRINT_VARIABLE]},
    }
    private_file(directory / 'github-settings.json', (json.dumps(github, indent=2) + '\n').encode('utf-8'))


def generate(output_dir=DEFAULT_OUTPUT, alias=DEFAULT_ALIAS, keystore=None, keytool=None, env=None):
    """Reserve a fresh private bundle; validate a reused private key without modifying its source."""
    env = dict(os.environ if env is None else env)
    output = Path(output_dir).absolute()
    if output.exists() or output.is_symlink():
        raise ValueError('Output already exists; reuse its key explicitly instead of replacing it')
    output = output.resolve()
    if output.is_relative_to(ROOT) and not output.is_relative_to((ROOT / 'keystore').resolve()):
        raise ValueError('Signing bundles inside this checkout must be under the ignored keystore directory')
    if not alias or any(character in alias for character in '\0\r\n'):
        raise ValueError('A nonempty, single-line key alias is required')
    source = Path(keystore).resolve() if keystore is not None else None
    if source is not None and not source.is_file():
        raise ValueError('Existing keystore must be a readable file')
    if source is None and any(env.get(name) for name in (*KEY_VARIABLES, FINGERPRINT_VARIABLE)):
        raise ValueError('Signing credentials are already loaded; use --keystore to retain the identity')
    keytool = keytool or locate_keytool(env)
    if source:
        store_password = password(env, KEY_VARIABLES[1], 'Existing keystore password: ')
        key_password = password(env, KEY_VARIABLES[3], 'Existing private-key password (Enter = keystore password): ', store_password)
    else:
        store_password, key_password = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
    env.update({KEY_VARIABLES[1]: store_password, KEY_VARIABLES[2]: alias, KEY_VARIABLES[3]: key_password})
    output.parent.mkdir(parents=True, exist_ok=True)
    with private_bundle(output, env), tempfile.TemporaryDirectory(prefix='.prepare-', dir=output) as temporary:
        staged = Path(temporary)
        key = staged / 'benchmark.jks'
        if source:
            private_file(key, source.read_bytes())
        else:
            run_private([keytool, '-genkeypair', '-storetype', 'JKS', '-keystore', key,
                         '-alias', alias, '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000',
                         '-dname', 'CN=IMU Nav Benchmark', '-storepass:env', KEY_VARIABLES[1],
                         '-keypass:env', KEY_VARIABLES[3]], env)
            key.chmod(0o600)
        certificate = staged / 'certificate.der'
        run_private([keytool, '-exportcert', '-keystore', key, '-alias', alias,
                     '-storepass:env', KEY_VARIABLES[1], '-file', certificate], env)
        # Certificate export alone also accepts trusted-certificate entries or a wrong private-key password.
        run_private([keytool, '-certreq', '-keystore', key, '-alias', alias,
                     '-storepass:env', KEY_VARIABLES[1], '-keypass:env', KEY_VARIABLES[3],
                     '-file', staged / 'request.csr'], env)
        digest = hashlib.sha256(certificate.read_bytes()).hexdigest()
        if env.get(FINGERPRINT_VARIABLE) and digest != fingerprint(env[FINGERPRINT_VARIABLE]):
            raise ValueError('Existing keystore differs from the loaded certificate fingerprint')
        values = {KEY_VARIABLES[0]: base64.b64encode(key.read_bytes()).decode('ascii'),
                  KEY_VARIABLES[1]: store_password, KEY_VARIABLES[2]: alias,
                  KEY_VARIABLES[3]: key_password, FINGERPRINT_VARIABLE: digest}
        write_exports(staged, values)
        # JSON contains the complete recoverable identity if a later file publication fails.
        for name in ('github-settings.json', 'benchmark.jks', 'env.sh', 'env.ps1'):
            (staged / name).rename(output / name)
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument('--keystore', type=Path, help='Reuse an existing private key; passwords are masked or supplied via environment')
    parser.add_argument('--alias', default=DEFAULT_ALIAS)
    parser.add_argument('--keytool', type=Path)
    args = parser.parse_args()
    try:
        generate(args.output_dir, args.alias, args.keystore, args.keytool)
    except (ValueError, OSError, subprocess.TimeoutExpired) as error:
        message = str(error) if isinstance(error, ValueError) else 'Signing environment generation could not complete'
        raise SystemExit(message) from None
    print('Private signing bundle created. Back it up; load env.sh or env.ps1 for local signing.')
    print('Use github-settings.json to configure Actions secrets and the certificate variable; no secrets were uploaded.')


if __name__ == '__main__':
    main()
