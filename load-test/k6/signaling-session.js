/**
 * PeakPic 시그널링 세션 부하 시나리오 (#90)
 *
 * 1 VU = 세션 1개. 한 VU가 owner/participant 소켓 2개를 모두 열어 실제 프로토콜 흐름을 재현한다:
 *   게스트 로그인 → CREATE_SESSION → JOIN_SESSION → DEVICE_SPEC → OFFER/ANSWER →
 *   ICE 버스트 → PING 유지 → END_SESSION
 *
 * 두 소켓이 같은 VU(같은 시계) 안에 있으므로, 페이로드에 심은 전송 시각과 수신 시각의 차이가
 * 곧 "발신 소켓 → 서버 → 수신 소켓" 편도 지연(E2E)이다. 서버 내부 몫은 Grafana의
 * ws_message_handle(처리시간)·lettuce_command(레디스 왕복)와 대조해 분해한다.
 *
 * 실행 예:
 *   k6 run load-test/k6/signaling-session.js                          # smoke (기본)
 *   k6 run -e SCENARIO=fixed -e SESSIONS=100 -e DURATION=5m ...       # 고정 부하 (before/after 비교)
 *   k6 run -e SCENARIO=ramp ...                                       # 용량 한계 탐색
 *   k6 run -e SDP_KB=9 ...                                            # 톰캣 8KB 한도 실험
 */
import http from 'k6/http';
import { WebSocket } from 'k6/websockets';
import { Counter, Rate, Trend } from 'k6/metrics';
import { buildCandidate, buildSdp, candidateTs, sdpTs } from './payloads.js';

// ═══ 파라미터 (환경변수로 조절, 기본값은 로컬 스펙 제한 환경 기준) ═══
const API = __ENV.API || 'http://localhost:8081';
const WS_URL = __ENV.WS_URL || API.replace(/^http/, 'ws') + '/signaling';
const SCENARIO = __ENV.SCENARIO || 'smoke';
const SESSIONS = Number(__ENV.SESSIONS || 50);       // fixed: 동시 세션 수
const DURATION = __ENV.DURATION || '3m';             // fixed: 유지 시간
const SESSION_SEC = Number(__ENV.SESSION_SEC || (SCENARIO === 'smoke' ? 8 : 40)); // 세션 1개 수명
const ICE_BURST = Number(__ENV.ICE_BURST || 10);     // 연결 수립 시 측당 ICE 후보 수
const SDP_KB = Number(__ENV.SDP_KB || 4);            // OFFER/ANSWER 크기(KB)
const PING_SEC = 25;                                 // 실제 클라이언트 하트비트 주기와 동일
const ESTABLISH_TIMEOUT_MS = 15000;
const JITTER = __ENV.JITTER !== '0';                 // 0이면 지터 해제 → 동시 몰림(스파이크) 조건 재현

// ═══ 커스텀 지표 ═══
const relayIceE2e = new Trend('relay_ice_e2e_ms');       // ICE 편도 지연 (소형 페이로드)
const relaySdpE2e = new Trend('relay_sdp_e2e_ms');       // OFFER/ANSWER 편도 지연 (대형 페이로드)
const createMs = new Trend('create_session_ms');         // CREATE→SESSION_CREATED (코드 발급+DB 쓰기)
const joinMs = new Trend('join_established_ms');         // JOIN 전송→owner의 PEER_JOINED (Lua+DB 쓰기)
const relaySent = new Counter('relay_msgs_sent');
const relayRecv = new Counter('relay_msgs_received');    // sent와의 차이 = 유실(강제 종료 포함)
const signalingErrors = new Counter('signaling_errors');
const sessionOk = new Rate('session_success');

const SCENARIOS = {
    // 스크립트 자체 검증: 세션 2개 × 1회
    smoke: { executor: 'per-vu-iterations', vus: 2, iterations: 1 },
    // 성능 측정용 고정 부하: 같은 조건 반복이 가능해야 개선 전/후 비교가 성립한다
    fixed: { executor: 'constant-vus', vus: SESSIONS, duration: DURATION, gracefulStop: '60s' },
    // 용량 한계 탐색: 계단식 증가 — 임계(오류율·P95) 돌파 시점의 동시 세션 수를 찾는다
    ramp: {
        executor: 'ramping-vus',
        startVUs: 0,
        gracefulStop: '60s',
        stages: [
            { duration: '1m', target: 50 },
            { duration: '2m', target: 150 },
            { duration: '2m', target: 300 },
            { duration: '2m', target: 500 },
            { duration: '1m', target: 0 },
        ],
    },
};
if (!SCENARIOS[SCENARIO]) throw new Error(`unknown SCENARIO: ${SCENARIO} (smoke|fixed|ramp)`);

export const options = {
    scenarios: { [SCENARIO]: SCENARIOS[SCENARIO] },
    thresholds: {
        relay_ice_e2e_ms: ['p(95)<200'],
        session_success: ['rate>0.99'], // ramp의 강제 종료 구간에서는 참고치로만 본다
    },
};

// VU마다 게스트 2명(owner/participant)을 최초 1회만 발급받아 재사용.
// deviceId를 VU 번호로 고정해 반복 실행에도 유저 수가 늘지 않는다.
let tokens = null;

function guestLogin(role) {
    const res = http.post(
        `${API}/auth/guest`,
        JSON.stringify({ deviceId: `loadtest-vu${__VU}-${role}` }),
        { headers: { 'Content-Type': 'application/json' } },
    );
    if (res.status !== 200) throw new Error(`guest login(${role}) failed: ${res.status} ${res.body}`);
    return res.json('accessToken');
}

let firstIteration = true;

export default function () {
    if (!tokens) tokens = { owner: guestLogin('owner'), participant: guestLogin('part') };

    // 파도 동기화 방지(1차 측정에서 관측): VU 전원이 같은 주기로 시작·종료하면
    // 실사용에 없는 동시 수립 스파이크가 생긴다 → 시작 시점과 세션 수명을 무작위로 흩뜨린다.
    const startDelayMs = (JITTER && SCENARIO !== 'smoke' && firstIteration) ? Math.random() * 20000 : 0;
    firstIteration = false;
    const sessionLifeMs = SESSION_SEC * 1000
            + (JITTER && SCENARIO !== 'smoke' ? Math.floor(Math.random() * 10000) : 0);

    setTimeout(() => runSession(sessionLifeMs), startDelayMs);
}

function runSession(sessionLifeMs) {
    let sessionCode = null;
    let established = false;
    let done = false;
    let participant = null;
    const timeouts = [];
    const intervals = [];
    const t = { create: 0, join: 0 };

    const owner = new WebSocket(`${WS_URL}?token=${tokens.owner}`);

    // 어떤 경로로 끝나든 반드시 여기로 수렴: 타이머·소켓을 정리해야 iteration이 종료된다
    const finish = (ok) => {
        if (done) return;
        done = true;
        sessionOk.add(ok);
        timeouts.forEach((id) => clearTimeout(id));
        intervals.forEach((id) => clearInterval(id));
        try { if (participant) participant.close(); } catch (e) { /* 이미 닫힘 */ }
        try { owner.close(); } catch (e) { /* 이미 닫힘 */ }
    };

    // 수립 실패 가드 + 전체 수명 가드 (정상 종료 신호를 못 받아도 세션은 반드시 끝난다)
    timeouts.push(setTimeout(() => {
        if (!established) { signalingErrors.add(1); finish(false); }
    }, ESTABLISH_TIMEOUT_MS));
    timeouts.push(setTimeout(() => finish(established), ESTABLISH_TIMEOUT_MS + sessionLifeMs));

    // ── 공통 도우미 ──
    const send = (sock, obj) => { if (!done) sock.send(JSON.stringify(obj)); };

    const onRelayed = (m) => {
        relayRecv.add(1);
        if (m.type === 'ICE_CANDIDATE') {
            const ts = candidateTs(m.candidate);
            if (ts) relayIceE2e.add(Date.now() - ts);
        } else if (m.type === 'OFFER' || m.type === 'ANSWER') {
            const ts = sdpTs(m.sdp);
            if (ts) relaySdpE2e.add(Date.now() - ts);
        }
    };

    // 트리클 ICE처럼 후보를 30ms 간격으로 흘린다 (몰아치기보다 실제 패턴에 가깝게)
    const sendIceBurst = (sock) => {
        for (let i = 0; i < ICE_BURST; i++) {
            timeouts.push(setTimeout(() => {
                send(sock, { type: 'ICE_CANDIDATE', sessionCode, candidate: buildCandidate(Date.now(), __VU * 100 + i) });
                relaySent.add(1);
            }, i * 30));
        }
    };

    const sendDeviceSpec = (sock, role) => {
        send(sock, {
            type: 'DEVICE_SPEC', sessionCode, role,
            maxZoom: 10.0, minZoom: 0.5, screenRatio: 1.7778,
            maxResolution: '3840x2160', osType: 'ANDROID',
        });
        relaySent.add(1);
    };

    const startPing = (sock) => {
        intervals.push(setInterval(() => send(sock, { type: 'PING' }), PING_SEC * 1000));
    };

    // ── owner 소켓: 세션 생성 → 참여 알림 받으면 교환 시작 ──
    owner.onopen = () => {
        t.create = Date.now();
        send(owner, { type: 'CREATE_SESSION' });
    };
    owner.onmessage = (e) => {
        const m = JSON.parse(e.data);
        switch (m.type) {
            case 'SESSION_CREATED':
                createMs.add(Date.now() - t.create);
                sessionCode = m.sessionCode;
                openParticipant();
                break;
            case 'PEER_JOINED':
                joinMs.add(Date.now() - t.join);
                established = true;
                // 실제 앱 순서: 스펙 교환 → 오퍼 → ICE, 이후 유휴 유지(PING) → 수명 종료
                sendDeviceSpec(owner, 'DIRECTOR');
                send(owner, { type: 'OFFER', sessionCode, sdpType: 'offer', sdp: buildSdp(SDP_KB, Date.now()) });
                relaySent.add(1);
                sendIceBurst(owner);
                startPing(owner);
                timeouts.push(setTimeout(() => {
                    send(owner, { type: 'END_SESSION', sessionCode });
                    timeouts.push(setTimeout(() => finish(true), 1000)); // 상대의 SESSION_ENDED 수신 여유
                }, sessionLifeMs));
                break;
            case 'OFFER': case 'ANSWER': case 'ICE_CANDIDATE': case 'DEVICE_SPEC':
                onRelayed(m);
                break;
            case 'ERROR':
                signalingErrors.add(1);
                finish(false);
                break;
            default: // PONG, PEER_DISCONNECTED 등은 흐름에 영향 없음
        }
    };
    owner.onerror = () => { if (!done) { signalingErrors.add(1); finish(false); } };
    owner.onclose = () => { if (!done) { signalingErrors.add(1); finish(false); } };

    // ── participant 소켓: 코드 받고 입장 → 오퍼에 응답 ──
    function openParticipant() {
        participant = new WebSocket(`${WS_URL}?token=${tokens.participant}`);
        participant.onopen = () => {
            t.join = Date.now();
            send(participant, { type: 'JOIN_SESSION', sessionCode });
        };
        participant.onmessage = (e) => {
            const m = JSON.parse(e.data);
            switch (m.type) {
                case 'OFFER':
                    onRelayed(m);
                    sendDeviceSpec(participant, 'CAMERA');
                    send(participant, { type: 'ANSWER', sessionCode, sdpType: 'answer', sdp: buildSdp(SDP_KB, Date.now()) });
                    relaySent.add(1);
                    sendIceBurst(participant);
                    startPing(participant);
                    break;
                case 'ANSWER': case 'ICE_CANDIDATE': case 'DEVICE_SPEC':
                    onRelayed(m);
                    break;
                case 'SESSION_ENDED':
                    finish(true);
                    break;
                case 'ERROR':
                    signalingErrors.add(1);
                    finish(false);
                    break;
                default: // PONG 등
            }
        };
        participant.onerror = () => { if (!done) { signalingErrors.add(1); finish(false); } };
        participant.onclose = () => { if (!done) { signalingErrors.add(1); finish(false); } };
    }
}
