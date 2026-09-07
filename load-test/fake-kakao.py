#!/usr/bin/env python3
"""가짜 침묵 카카오 (#125 장애 주입 실험).

모든 요청에 120초 침묵 후 500 — 어떤 타임아웃 설정보다 길어서, 관측되는 지연은 전부
서버 앱 쪽 타임아웃이 결정한다 ("외부가 응답하지 않는" 최악 케이스의 재현).

사용:  python3 fake-kakao.py [포트=9999]
앱 컨테이너에서는 host.docker.internal:9999 로 접근한다.
"""
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SILENCE_SECONDS = 120


class SilentHandler(BaseHTTPRequestHandler):
    def _hang(self):
        time.sleep(SILENCE_SECONDS)
        try:
            self.send_response(500)
            self.end_headers()
        except Exception:
            pass  # 클라이언트가 먼저 타임아웃으로 끊은 경우

    do_GET = _hang
    do_POST = _hang

    def log_message(self, *args):
        pass  # 200 VU 요청 로그 홍수 방지


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9999
    print(f"silent kakao on :{port} — every request sleeps {SILENCE_SECONDS}s (Ctrl+C to stop)")
    ThreadingHTTPServer(("0.0.0.0", port), SilentHandler).serve_forever()
