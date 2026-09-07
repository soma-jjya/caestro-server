// 외부 인증 서버 장애 전염 실험 (#125)
//
// 가설: 카카오가 침묵하면(가짜 카카오) 로그인 요청이 톰캣 스레드를 점유하고,
// 모놀리스는 스레드풀을 공유하므로 카카오와 무관한 게스트 로그인까지 전염된다.
// 타임아웃(3s)+서킷브레이커가 이 전염을 몇 초 안에 차단하는지를 before/after로 잰다.
//
//   kakao_attack: ATTACK_VUS(기본 200 = 톰캣 기본 스레드 수)가 카카오 로그인 연타 — 장애 유발자
//   bystander:    10 VU가 게스트 로그인 반복 — 전염의 피해자 측정계 (카카오와 무관한 경로)
//
// 실행 전제: fake-kakao.py 기동 + 앱을 KAKAO_USERINFOURI=가짜 주소로 기동 (절차는 README)
//   before: EXTERNAL_HTTP_RESPONSETIMEOUTMS=60000 (타임아웃 부재의 근사)
//   after:  기본값 (3s + 서킷)
import http from 'k6/http';
import { sleep, check } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const API = __ENV.API || 'http://localhost:8081';
const ATTACK_VUS = Number(__ENV.ATTACK_VUS || 200);
const DURATION = __ENV.DURATION || '3m';

const bystanderMs = new Trend('bystander_guest_ms', true);
const attackMs = new Trend('kakao_login_ms', true);
const attack502 = new Counter('attack_502_upstream_fail');   // 외부 실패(타임아웃 소진 포함)
const attack503 = new Counter('attack_503_circuit_open');    // 서킷 open — 즉시 거절의 증거
const attackOther = new Counter('attack_other_status');

export const options = {
    scenarios: {
        kakao_attack: {
            executor: 'constant-vus',
            vus: ATTACK_VUS,
            duration: DURATION,
            exec: 'attack',
            gracefulStop: '5s', // 종료 시 매달린 요청은 끊는다 (before는 요청당 최대 120s)
        },
        bystander: {
            executor: 'constant-vus',
            vus: 10,
            duration: DURATION,
            exec: 'bystander',
            gracefulStop: '5s',
        },
    },
    // 요약에 지표를 항상 노출하기 위한 형식적 임계값
    thresholds: {
        bystander_guest_ms: ['p(95)>=0'],
        kakao_login_ms: ['p(95)>=0'],
    },
};

export function attack() {
    const res = http.post(`${API}/auth/kakao/token`,
        JSON.stringify({ accessToken: 'outage-experiment' }),
        {
            headers: { 'Content-Type': 'application/json' },
            timeout: '150s', // before: 60s 타임아웃 × 재시도 2회 = 최대 ~120s를 끝까지 관측
            tags: { name: 'kakao_login' },
        });
    attackMs.add(res.timings.duration);
    if (res.status === 502) attack502.add(1);
    else if (res.status === 503) attack503.add(1);
    else attackOther.add(1);
    sleep(0.2);
}

export function bystander() {
    // VU당 고정 deviceId — 최초 1회만 유저 생성, 이후엔 조회+JWT 발급 (정상 코어 경로)
    const res = http.post(`${API}/auth/guest`,
        JSON.stringify({ deviceId: `bystander-${__VU}` }),
        {
            headers: { 'Content-Type': 'application/json' },
            timeout: '70s', // 전염 구간의 실제 지연을 에러가 아니라 숫자로 관측
            tags: { name: 'guest_login' },
        });
    bystanderMs.add(res.timings.duration);
    check(res, { 'guest 200': (r) => r.status === 200 });
    sleep(2);
}
