#!/usr/bin/env python3
"""Gate every packaged 64-bit library's ELF layout and the final APK's 16 KB ZIP alignment."""
import argparse
import json
from pathlib import Path
import struct
import subprocess
import zipfile

PAGE_SIZE = 16384
ABIS = {'arm64-v8a', 'x86_64'}
PT_LOAD = 1
PT_GNU_RELRO = 0x6474E552


def elf_errors(data):
    """Read ELF64 program headers directly, including prebuilt libraries supplied by dependencies."""
    if len(data) < 64 or data[:6] != b'\x7fELF\x02\x01':
        return ['Not a little-endian ELF64 library']
    offset = struct.unpack_from('<Q', data, 32)[0]
    entry_size, count = struct.unpack_from('<HH', data, 54)
    if entry_size < 56 or count == 0 or offset + entry_size * count > len(data):
        return ['Invalid or missing ELF program headers']
    errors, loads = [], 0
    for index in range(count):
        kind, _, file_offset, virtual_address, _, file_size, memory_size, alignment = struct.unpack_from('<IIQQQQQQ', data, offset + index * entry_size)
        if kind == PT_LOAD:
            loads += 1
            if alignment < PAGE_SIZE or alignment & (alignment - 1):
                errors.append(f'LOAD[{index}] alignment={alignment}, requires a power of two >= {PAGE_SIZE}')
            elif file_offset % alignment != virtual_address % alignment:
                errors.append(f'LOAD[{index}] file/virtual offsets are incongruent')
            if file_size > memory_size or file_offset + file_size > len(data):
                errors.append(f'LOAD[{index}] invalid segment bounds')
        elif kind == PT_GNU_RELRO and (virtual_address + memory_size) % PAGE_SIZE:
            errors.append(f'GNU_RELRO[{index}] end is not {PAGE_SIZE}-aligned')
    if not loads:
        errors.append('Missing LOAD segment')
    return errors


def verify_apk(apk, zipalign):
    report = {'apk': str(apk), 'libraries': [], 'errors': []}
    with zipfile.ZipFile(apk) as archive:
        for name in sorted(archive.namelist()):
            parts = name.split('/')
            if len(parts) != 3 or parts[0] != 'lib' or parts[1] not in ABIS or not name.endswith('.so'):
                continue
            errors = elf_errors(archive.read(name))
            report['libraries'].append({'name': name, 'errors': errors})
            report['errors'].extend(f'{name}: {error}' for error in errors)
    if not report['libraries']:
        report['errors'].append('No packaged 64-bit native libraries were checked')
    elif not any(library['name'].endswith('/libimu_nav_jni.so') for library in report['libraries']):
        report['errors'].append('Missing IMU Nav 64-bit JNI library')
    result = subprocess.run([str(zipalign), '-v', '-c', '-P', '16', '4', str(apk)], text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False)
    report['zipalign'] = {'exit_code': result.returncode, 'output': result.stdout}
    if result.returncode:
        report['errors'].append('zipalign rejected the final APK')
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--zipalign', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('apks', type=Path, nargs='+')
    args = parser.parse_args()
    reports = []
    for apk in args.apks:
        try:
            reports.append(verify_apk(apk, args.zipalign))
        except (OSError, ValueError, zipfile.BadZipFile, struct.error) as error:
            reports.append({'apk': str(apk), 'errors': [str(error)]})
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps({'page_size': PAGE_SIZE, 'apks': reports}, indent=2) + '\n')
    for report in reports:
        print(f"{report['apk']}: {'FAIL' if report['errors'] else 'PASS'}")
        for error in report['errors']:
            print(f'  {error}')
    raise SystemExit(int(any(report['errors'] for report in reports)))


if __name__ == '__main__':
    main()
