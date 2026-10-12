"""Collect dependency licence files for the portable distribution, using Cargo's resolved metadata."""
import json
from pathlib import Path
import sys


def main():
    metadata = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8-sig'))
    with Path(sys.argv[2]).open('w', encoding='utf-8') as output:
        output.write('IMU Nav Desktop third-party notices\n\n')
        for package in sorted(metadata['packages'], key=lambda package: (package['name'], package['version'])):
            if package['source'] is None:
                continue
            output.write(f"\n{package['name']} {package['version']} — {package.get('license') or 'See licence files'}\n")
            root = Path(package['manifest_path']).parent
            notices = sorted(path for path in root.rglob('*') if path.is_file() and
                             (path.name.lower().startswith(('license', 'licence', 'copying', 'notice'))))
            if package.get('license_file'):
                explicit = root / package['license_file']
                if explicit not in notices:
                    notices.append(explicit)
            for notice in notices:
                output.write(f'\n--- {notice.name} ---\n')
                output.write(notice.read_text(encoding='utf-8', errors='replace'))
                output.write('\n')


if __name__ == '__main__':
    main()
