#!/usr/bin/env python3
"""Checks that this tree is upstream robotOS at the pinned commit plus only Herdr's known edits.

usage: tools/upstream-check.py <path to a robotOS clone> [commit]   (commit defaults to UPSTREAM.md's pin)
"""
import os
import re
import subprocess
import sys

ALLOWED = {
    "app/src/main/AndroidManifest.xml",
    "app/src/main/java/dev/r1ptt/App.kt",
    "app/src/main/java/dev/r1ptt/net/TtsClient.kt",
    "app/src/main/res/values/colors.xml",
    "tools/provision.sh",
    "README.md",
    "tools/flash-gsi.sh",
    "NOTICE",
    "app/build.gradle.kts",
}

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
clone = sys.argv[1]
commit = sys.argv[2] if len(sys.argv) > 2 else re.search(r"`([0-9a-f]{40})`", open(os.path.join(root, "UPSTREAM.md")).read()).group(1)
tree = subprocess.run(["git", "-C", clone, "ls-tree", "-r", commit], capture_output=True, text=True, check=True).stdout

drift = []
for line in tree.splitlines():
    meta, path = line.split("\t", 1)
    local = os.path.join(root, path)
    ours = subprocess.run(["git", "hash-object", local], capture_output=True, text=True).stdout.strip() if os.path.isfile(local) else None
    if ours != meta.split()[2] and path not in ALLOWED:
        drift.append(f"{'missing' if ours is None else 'modified'}  {path}")

print(f"upstream {commit[:12]}: {len(tree.splitlines())} files, {len(drift)} unexpected differences")
print("\n".join(drift))
sys.exit(1 if drift else 0)
