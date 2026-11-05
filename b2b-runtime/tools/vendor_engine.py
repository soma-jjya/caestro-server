"""One-time, explicit snapshot; never overwrite an already-vendored engine."""
import hashlib
import json
from pathlib import Path
import re
import shutil

root = Path(__file__).resolve().parents[2]
dest = root / 'b2b-runtime' / 'engine'
if dest.exists():
    raise SystemExit('engine already exists; review upstream changes explicitly')
src = root / 'src/main/java'
paths = {str(p.relative_to(src)).replace('\\', '/'): p for p in src.rglob('*.java')}
base = 'com/caestro/server/'
roots = [
    'domain/b2b/', 'domain/fill/dto/', 'domain/fill/entity/', 'domain/fill/repository/',
    'domain/fill/store/', 'domain/catalog/entity/',
]
selected = {p for p in paths if any(p.startswith(base + r) for r in roots)
            and '/controller/' not in p and not p.endswith('B2bCleanupScheduler.java')}
selected.update(base + p for p in [
    'domain/fill/service/FillService.java', 'domain/fill/service/FillCodeGenerator.java',
    'domain/fill/service/FillWriteGuard.java', 'domain/user/entity/User.java',
    'domain/user/repository/UserRepository.java', 'global/exception/GlobalExceptionHandler.java',
    'global/ratelimit/RedisRateLimiter.java', 'global/config/JacksonConfig.java',
    'domain/catalog/store/RdsCatalogAssetStore.java', 'domain/catalog/store/S3CatalogAssetStore.java',
])
while True:
    before = len(selected)
    for p in list(selected):
        source = paths[p].read_text(encoding='utf-8')
        for imp in re.findall(r'import (com\.caestro\.[\w.*]+);', source):
            relative = imp.replace('.', '/')
            if relative.endswith('/*'):
                selected.update(q for q in paths if q.rsplit('/', 1)[0] == relative[:-2])
            else:
                while relative and relative + '.java' not in paths:
                    relative = relative.rsplit('/', 1)[0] if '/' in relative else ''
                if relative:
                    selected.add(relative + '.java')
    if len(selected) == before:
        break
manifest = {'upstream': 'soma-jjya/caestro-server',
            'commit': 'c867e00a19b8a2ed60300ffef7ba176f0ba9ec0b', 'files': {}}
for p in sorted(selected):
    target = dest / 'src/main/java' / p
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(paths[p], target)
    manifest['files'][str(target.relative_to(dest)).replace('\\', '/')] = hashlib.sha256(target.read_text(encoding='utf-8').encode('utf-8')).hexdigest()
for relative in ['src/main/resources/scripts/rate_limit.lua']:
    target = dest / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(root / relative, target)
    manifest['files'][relative] = hashlib.sha256(target.read_text(encoding='utf-8').encode('utf-8')).hexdigest()
(dest / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
for p in ['gradlew', 'gradlew.bat', 'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties']:
    target = root / 'b2b-runtime' / p
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(root / p, target)
print(f'Vendored {len(selected)} Java files; B2C sources unchanged')
