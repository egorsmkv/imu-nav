#!/usr/bin/env bash
# Pin both upstream release and bytes; no latest-version or unchecked tool execution.
set -euo pipefail
bundletool_path=${1:?Output jar path required}
curl --fail --location --retry 3 'https://github.com/google/bundletool/releases/download/1.18.3/bundletool-all-1.18.3.jar' --output "$bundletool_path"
printf 'a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29  %s\n' "$bundletool_path" | sha256sum --check --strict
