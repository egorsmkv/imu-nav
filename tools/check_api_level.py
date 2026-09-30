#!/usr/bin/env python3
"""
Find calls in a built APK to Android/Java platform methods newer than the app's minSdk.

Android Lint checks our own sources, but not the bytecode of libraries (GraphHopper, OkHttp…).
A library call to a method the phone does not have crashes with NoSuchMethodError, and only on
older Android versions, so it is easy to miss when testing on a new phone. This script reads every
method call in the APK's dex files and looks each one up in the SDK's api-versions.xml.

R8 moves calls to newer APIs into small helper classes ("ApiModelOutline"); AndroidX guards those
with Build.VERSION checks, so they are fine. Reported are calls from code that is *not* known to be
guarded: review each one. The exit code is 1 if any unreviewed call remains.

Usage:
    ./gradlew :app:assemblePlayRelease
    tools/check_api_level.py app/build/outputs/apk/play/release/app-play-release.apk \
        app/build/outputs/mapping/playRelease/mapping.txt
"""
import collections
import glob
import os
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

MIN_SDK = 26

# Callers that check Build.VERSION.SDK_INT themselves before using newer APIs.
GUARDED_PREFIXES = (
    "androidx.", "android.support.", "kotlinx.", "kotlin.", "org.maplibre.", "okhttp3.", "okio.", "com.google.",
    # Our own Android code is checked by Lint (NewApi), which understands its SDK_INT checks.
    "org.imunav.app.",
)

# Library code that never runs on the phone (desktop pack builder / elevation download).
DESKTOP_ONLY = (
    "com.graphhopper.reader.dem.", "com.graphhopper.reader.osm.", "com.graphhopper.util.Downloader",
    "com.graphhopper.routing.util.parsers.",
)


def sdk_dir():
    return os.environ.get("ANDROID_HOME") or os.path.expanduser("~/Library/Android/sdk")


def newest(pattern):
    found = sorted(glob.glob(os.path.join(sdk_dir(), pattern)))
    if not found:
        sys.exit(f"not found in the Android SDK: {pattern}")
    return found[-1]


def load_api_levels():
    """(class, method+descriptor) -> API level it appeared in; plus class levels and the class hierarchy."""
    root = ET.parse(newest("platforms/android-*/data/api-versions.xml")).getroot()
    methods, classes, parents = {}, {}, collections.defaultdict(list)
    for c in root.iter("class"):
        name = c.get("name")
        since = int(c.get("since", "1").split(".")[0])
        classes[name] = since
        for m in c.iter("method"):
            methods[(name, m.get("name"))] = int(m.get("since", str(since)).split(".")[0])
        for e in list(c.iter("extends")) + list(c.iter("implements")):
            parents[name].append(e.get("name"))
    return methods, classes, parents


def level_of(methods, parents, cls, method, seen=None):
    if (cls, method) in methods:
        return methods[(cls, method)]
    seen = seen or set()
    for p in parents.get(cls, []):
        if p not in seen:
            seen.add(p)
            found = level_of(methods, parents, p, method, seen)
            if found is not None:
                return found
    return None


def load_mapping(path):
    """R8 mapping: obfuscated class -> original class, and (class, obfuscated method) -> original method."""
    classes, methods, current = {}, {}, None
    if not path:
        return classes, methods
    for line in open(path, encoding="utf-8"):
        if not line.startswith(" ") and line.rstrip().endswith(":") and " -> " in line:
            original, obfuscated = line.rstrip()[:-1].split(" -> ")
            classes[obfuscated] = original
            current = obfuscated
        elif current and " -> " in line and "(" in line:
            original, obfuscated = line.strip().rsplit(" -> ", 1)
            name = original.split("(")[0].split(" ")[-1].split(":")[-1]
            methods.setdefault((current, obfuscated), name)
    return classes, methods


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    apk, mapping = sys.argv[1], (sys.argv[2] if len(sys.argv) > 2 else None)
    methods, classes, parents = load_api_levels()
    class_map, method_map = load_mapping(mapping)
    dexdump = newest("build-tools/*/dexdump")

    # caller -> set of (api level, callee); outline helpers are resolved to their callers below.
    calls = collections.defaultdict(set)
    with tempfile.TemporaryDirectory() as tmp:
        with zipfile.ZipFile(apk) as z:
            dexes = [n for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n)]
            z.extractall(tmp, dexes)
        for dex in dexes:
            out = subprocess.run([dexdump, "-d", os.path.join(tmp, dex)], capture_output=True, text=True, errors="replace").stdout
            cls = meth = None
            for line in out.splitlines():
                if line.startswith("  Class descriptor"):
                    cls = line.split("'")[1][1:-1].replace("/", ".")
                elif line.strip().startswith("name") and "'" in line:
                    meth = line.split("'")[1]
                m = re.search(r"invoke-[a-z/-]+ \{[^}]*\}, L([^;]+);\.([^:]+):(\([^ ]*)", line)
                if m and cls:
                    calls[(cls, meth)].add((m.group(1), m.group(2) + m.group(3)))

    def readable(cls, meth):
        original_cls, original_meth = class_map.get(cls, cls), method_map.get((cls, meth), meth)
        # R8 may move a method into another class; the mapping then names its real class ("a.b.C.m").
        if "." in original_meth:
            original_cls, original_meth = original_meth.rsplit(".", 1)
        return original_cls, original_meth

    # Step 1: platform calls newer than minSdk, by (obfuscated) caller.
    direct = collections.defaultdict(set)
    for caller, callees in calls.items():
        for callee_cls, callee_method in callees:
            if not callee_cls.startswith(("java/", "javax/", "android/")):
                continue
            level = level_of(methods, parents, callee_cls, callee_method)
            if level is None:
                level = classes.get(callee_cls, 0)
            if level > MIN_SDK:
                direct[caller].add((level, f"{callee_cls.replace('/', '.')}.{callee_method}"))

    # Step 2: R8 outline helpers stand in for the real callers: attribute their calls to whoever calls them.
    findings = collections.defaultdict(set)
    for caller, newer in direct.items():
        original_cls, original_meth = readable(*caller)
        if "ExternalSyntheticApiModelOutline" in original_cls or "ExternalSynthetic" in original_cls:
            obf_cls = caller[0].replace(".", "/")
            for real_caller, callees in calls.items():
                if any(c == obf_cls and m.split("(")[0] == caller[1] for c, m in callees):
                    findings[readable(*real_caller)] |= newer
        else:
            findings[(original_cls, original_meth)] |= newer

    problems = 0
    for (cls, meth), newer in sorted(findings.items()):
        if cls.startswith(GUARDED_PREFIXES) or cls.startswith(DESKTOP_ONLY):
            continue
        problems += 1
        for level, callee in sorted(newer):
            print(f"API {level:>2}  {cls}.{meth}  ->  {callee}")
    print(f"{problems} caller(s) use APIs newer than minSdk {MIN_SDK} without a known guard.")
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
