#!/usr/bin/env python3
"""Build the dependency-free attendee app; preserve parent-generated assets/config."""
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parent
DIST = ROOT / 'dist'
DIST.mkdir(parents=True, exist_ok=True)
for source_root in (ROOT / 'src', ROOT / 'public'):
    if not source_root.exists():
        continue
    for source in source_root.rglob('*'):
        if source.is_file():
            target = DIST / source.relative_to(source_root)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
print(f'Built attendee app: {DIST}')
