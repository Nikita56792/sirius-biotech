"""Clear only generated Java/D8 outputs, not inputs or signing keys."""
from pathlib import Path
import shutil
root = Path(__file__).resolve().parent.parent
for name in ('classes', 'dex'):
    target = root / 'build' / name
    if target.exists():
        if target.is_symlink():
            raise SystemExit('Refusing symlink output: ' + str(target))
        shutil.rmtree(target)
    target.mkdir(parents=True)
