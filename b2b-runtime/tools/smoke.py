"""Real MySQL/Redis journey for compose.test.yml; never targets a remote server."""
import json
import os
import struct
import zlib
from urllib.request import Request, build_opener, ProxyHandler, HTTPRedirectHandler
from urllib.error import HTTPError

PORT = os.environ.get('B2B_TEST_PORT', '18090')
if PORT not in ('18090', '8080'):
    raise SystemExit('Only the isolated local test ports are accepted')
BASE = f'http://127.0.0.1:{PORT}/projects/yuja-test'
PUBLIC_BASE = 'http://127.0.0.1:18090/projects/yuja-test'
ADMIN = {'X-B2B-Admin-Key': 'isolated-test-admin-012345678901234567890123456789'}
def chunk(kind, data):
    return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
PNG = (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 32, 128, 8, 6, 0, 0, 0))
       + chunk(b'IDAT', zlib.compress((b'\0' + b'\xff\xff\x00\xff' * 32) * 128)) + chunk(b'IEND', b''))

class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise AssertionError('isolated test must never follow redirects to S3/CDN')

opener = build_opener(ProxyHandler({}), NoRedirect())

def call(method, path, expected=200, body=None, headers=None, raw=False):
    headers = dict(headers or {})
    if body is not None and not isinstance(body, bytes):
        body = json.dumps(body).encode()
        headers['Content-Type'] = 'application/json'
    request = Request(BASE + path, data=body, headers=headers, method=method)
    try:
        response = opener.open(request, timeout=15)
    except HTTPError as error:
        response = error
    with response:
        payload = response.read()
        assert response.status == expected, (method, path, response.status, payload[:300])
        return payload if raw or not payload else json.loads(payload)

def main():
    call('GET', '/actuator/health/readiness')
    call('GET', '/admin/project', expected=403)
    # A different context is rejected by the real embedded servlet container.
    with_missing_slug = Request(f'http://127.0.0.1:{PORT}/projects/other-test/auth/guest', data=b'', method='POST')
    try:
        opener.open(with_missing_slug, timeout=15)
        raise AssertionError('foreign slug accepted')
    except HTTPError as error:
        assert error.code == 404
        error.close()
    image = call('POST', '/admin/images', body=PNG, headers={**ADMIN, 'Content-Type': 'image/png'})
    project = call('PUT', '/admin/project/frames', headers=ADMIN, body={'frames': [{
        'name': 'smoke', 'layout': 'STRIP', 'imageId': image['id'],
        'windows': [{'x': 0, 'y': i / 4, 'w': 1, 'h': 0.25, 'rot': 0} for i in range(4)]}]})
    call('POST', '/admin/project/publish', headers=ADMIN)
    assert len(call('GET', '/')['frames']) == 1
    tokens = call('POST', '/auth/guest')
    refreshed = call('POST', '/auth/refresh', body={'refreshToken': tokens['refreshToken']})
    call('POST', '/auth/refresh', expected=401, body={'refreshToken': tokens['refreshToken']})
    bearer = {'Authorization': 'Bearer ' + refreshed['accessToken']}
    frame = project['frames'][0]
    fill = call('POST', '/fills', expected=201, headers=bearer, body={'frameId': frame['id'], 'frameVersion': frame['version']})
    code = fill['fill']['code']
    assert fill['shareUrl'] == 'https://peakpic.app/go/yuja-test/' + code
    for index in range(4):
        call('PUT', f'/fills/{code}/slots/{index}', headers={**bearer, 'Content-Type': 'image/png'}, body=PNG)
    complete = call('GET', '/fills/' + code, headers=bearer)
    for slot in complete['fill']['slots']:
        assert slot['imageUrl'].startswith(PUBLIC_BASE + '/fills/')
        assert call('GET', slot['imageUrl'][len(PUBLIC_BASE):], raw=True) == PNG
    call('PUT', '/admin/project/frames', expected=409, headers=ADMIN, body={'frames': []})
    print('PASS: real MySQL/Redis, refresh replay rejected, four slots readable, foreign slug rejected')

if __name__ == '__main__':
    main()
