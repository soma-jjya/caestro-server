"""Verify the pinned engine, normalized for Git LF/CRLF checkouts."""
from pathlib import Path
import hashlib
import json

root = Path(__file__).resolve().parents[1] / 'engine'
manifest = json.loads((root / 'manifest.json').read_text(encoding='utf-8'))
actual = {str(p.relative_to(root)).replace('\\', '/') for p in (root / 'src').rglob('*') if p.is_file()}
if actual != set(manifest['files']):
    raise SystemExit('engine file set changed; review the extraction boundary')
for name, expected in manifest['files'].items():
    digest = hashlib.sha256((root / name).read_text(encoding='utf-8').encode('utf-8')).hexdigest()
    if digest != expected:
        raise SystemExit('engine differs from pinned upstream: ' + name)
if any('/controller/' in p or '/domain/auth/' in p or '/domain/signaling/' in p for p in actual):
    raise SystemExit('B2C HTTP/auth/signaling code must not enter the isolated runtime')
print(f'Pinned engine verified: {len(actual)} files, upstream {manifest["commit"][:7]}')
