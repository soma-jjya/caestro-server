/**
 * PeakPic 시그널링 세션 부하 시나리오 (#90) + 재연결 계약 재현 (#105)
 *
 * 1 VU = 세션 1개. 한 VU가 owner/participant 소켓 2개를 모두 열어 실제 프로토콜 흐름을 재현한다:
 *   게스트 로그인 → CREATE_SESSION → JOIN_SESSION → DEVICE_SPEC → OFFER/ANSWER →
 *   ICE 버스트 → PING 유지 → END_SESSION
 *
 * 두 소켓이 같은 VU(같은 시계) 안에 있으므로, 페이로드에 심은 전송 시각과 수신 시각의 차이가
 * 곧 "발신 소켓 → 서버 → 수신 소켓" 편도 지연(E2E)이다. 서버 내부 몫은 Grafana의
 * ws_message_handle(처리시간)·lettuce_command(레디스 왕복)와 대조해 분해한다.
 *
 * 재연결(#105): 예기치 않은 close를 만나면 실제 앱 계약대로 재접속한다 —
 *   1012(서버 재시작)는 즉시, 그 외는 지수 backoff+jitter → 같은 토큰으로 JOIN_SESSION(takeover)
 *   → SESSION_RESUMED 수신까지가 "복원 시간". 배포 중 세션 생존을 재는 도구다.
 *
 * 실행 예:
 *   k6 run load-test/k6/signaling-session.js                          # smoke (기본)
 *   k6 run -e SCENARIO=fixed -e SESSIONS=100 -e DURATION=5m ...       # 고정 부하 (before/after 비교)
 *   k6 run -e SCENARIO=ramp ...                                       # 용량 한계 탐색
 *   k6 run -e SDP_KB=9 ...                                            # 톰캣 8KB 한도 실험
 *   k6 run -e SCENARIO=fixed -e SESSIONS=50 -e SESSION_SEC=300 -e PROBE_SEC=2 ...  # 배포 중 생존 측정
 *   k6 run -e RECONNECT=0 ...                                         # close = 실패로 취급 (#90 당시 동작)
 */
import http from 'k6/http';
import { sleep } from 'k6';
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
// ── 재연결 계약 (#105) ──
const RECONNECT = __ENV.RECONNECT !== '0';           // 0이면 예기치 않은 close = 세션 실패 (#90 당시 동작)
const PROBE_SEC = Number(__ENV.PROBE_SEC || 0);      // >0: 수립 후 양쪽이 N초마다 ICE 1건 전송 → 공백 중 유실(sent−received) 측정
const RESUME_TIMEOUT_MS = Number(__ENV.RESUME_TIMEOUT_SEC || 15) * 1000; // 최초 close부터 복원까지 허용 시간
const MAX_BACKOFF_MS = 30000;
const MAX_IMMEDIATE_1012 = 2;                        // 연속 1012에 즉시 재시도하는 상한 — 넘으면 backoff (드레인 중 인스턴스에 재착지 시 루프 방지)
// 서버의 게스트 발급 리밋(IP당 N/분, #129)을 도구가 준수한다 — k6는 한 IP에서 VU당 토큰 2개를
// 발급하므로 초과분 VU는 다음 창까지 대기한다. 로컬에서 한도를 env로 풀었다면 같은 값을 넘길 것.
const GUEST_LIMIT = Number(__ENV.GUEST_LIMIT || 30);

// ═══ 커스텀 지표 ═══
const relayIceE2e = new Trend('relay_ice_e2e_ms');       // ICE 편도 지연 (소형 페이로드)
const relaySdpE2e = new Trend('relay_sdp_e2e_ms');       // OFFER/ANSWER 편도 지연 (대형 페이로드)
const createMs = new Trend('create_session_ms');         // CREATE→SESSION_CREATED (코드 발급+DB 쓰기)
const joinMs = new Trend('join_established_ms');         // JOIN 전송→owner의 PEER_JOINED (Lua+DB 쓰기)
const relaySent = new Counter('relay_msgs_sent');
const relayRecv = new Counter('relay_msgs_received');    // sent와의 차이 = 유실(강제 종료·재접속 공백 포함)
const signalingErrors = new Counter('signaling_errors');
const sessionOk = new Rate('session_success');
// ── 재연결 (#105) ──
const unexpectedClose = new Counter('ws_unexpected_close');      // 태그 code(종료 코드)·role·phase(establishing|established)
const closeEpoch = new Trend('unexpected_close_epoch_ms');        // 종료 시각(epoch). max−min = 종료가 흩어진 폭(드레인 jitter 증거)
const resumeMs = new Trend('reconnect_resume_ms');                // 최초 close → SESSION_RESUMED
const resumeOk = new Rate('resume_success');                      // 재접속 후 복원 성공률
const reconnectFailed = new Counter('reconnect_attempt_failed');  // 재접속 시도 자체가 실패(서버 부재) → backoff 후 재시도
const peerDisconnectedSeen = new Counter('peer_disconnected_seen'); // 상대 화면이 본 이탈 통지
const peerReconnectedSeen = new Counter('peer_reconnected_seen');   // 상대 화면이 본 복귀 통지

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
        // 아래는 판정이 아니라 요약에 태그별 분해를 노출하기 위한 항목 (count>=0 은 항상 통과)
        'ws_unexpected_close{phase:established}': ['count>=0'],
        'ws_unexpected_close{phase:establishing}': ['count>=0'],
        'ws_unexpected_close{code:1000}': ['count>=0'],
        'ws_unexpected_close{code:1001}': ['count>=0'],
        'ws_unexpected_close{code:1006}': ['count>=0'],
        'ws_unexpected_close{code:1012}': ['count>=0'],
    },
};

// VU마다 게스트 2명(owner/participant)을 최초 1회만 발급받아 재사용.
// deviceId를 VU 번호로 고정해 반복 실행에도 유저 수가 늘지 않는다.
// 재접속 때도 같은 토큰을 쓴다(계약: /auth/guest 재호출 금지) — userId가 같아야 takeover가 된다.
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
    if (!tokens) {
        // 발급 페이싱 (#129): VU를 창 단위 그룹(창당 GUEST_LIMIT/2명)으로 나눠, 뒷 그룹은
        // 자기 창이 열릴 때까지 대기 — 한 IP 100회 버스트가 서버 리밋(429)에 걸리지 않게 한다
        const vusPerWindow = Math.max(1, Math.floor(GUEST_LIMIT / 2));
        const windowIdx = Math.floor((__VU - 1) / vusPerWindow);
        if (windowIdx > 0) sleep(windowIdx * 61);
        tokens = { owner: guestLogin('owner'), participant: guestLogin('part') };
    }

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
    let established = false;   // owner가 PEER_JOINED를 받음
    let answered = false;      // owner가 ANSWER를 받음 = P2P 협상 완료로 간주
    let done = false;
    const sockets = { owner: null, participant: null }; // 역할별 "현재" 소켓 — 재접속하면 새 소켓으로 교체된다
    const pending = { owner: null, participant: null }; // 재접속 중이면 { closedAt } (SESSION_RESUMED 대기)
    const attempts = { owner: 0, participant: 0 };      // 연속 재접속 시도 횟수 (backoff 지수, 성공 시 0)
    const immediate1012 = { owner: 0, participant: 0 }; // 연속 1012 즉시 재시도 횟수 (성공 시 0)
    const pingStarted = { owner: false, participant: false };
    const timeouts = [];
    const intervals = [];
    const t = { create: 0, join: 0 };
    const extraMs = RECONNECT ? RESUME_TIMEOUT_MS : 0;  // 재접속을 허용하면 수립·수명 가드에 그만큼 여유를 준다

    // 어떤 경로로 끝나든 반드시 여기로 수렴: 타이머·소켓을 정리해야 iteration이 종료된다
    const finish = (ok) => {
        if (done) return;
        done = true;
        sessionOk.add(ok);
        timeouts.forEach((id) => clearTimeout(id));
        intervals.forEach((id) => clearInterval(id));
        for (const role of ['owner', 'participant']) {
            try { if (sockets[role]) sockets[role].close(); } catch (e) { /* 이미 닫힘 */ }
        }
    };

    // 수립 실패 가드 + 전체 수명 가드 (정상 종료 신호를 못 받아도 세션은 반드시 끝난다)
    timeouts.push(setTimeout(() => {
        if (!established) { signalingErrors.add(1); finish(false); }
    }, ESTABLISH_TIMEOUT_MS + extraMs));
    timeouts.push(setTimeout(() => finish(established), ESTABLISH_TIMEOUT_MS + sessionLifeMs + extraMs));

    // ── 공통 도우미 ──
    // 항상 "현재" 소켓으로 보낸다: 재접속 뒤에도 PING·프로브 인터벌이 새 소켓을 자연히 쓴다
    const send = (role, obj) => {
        const s = sockets[role];
        if (done || !s || pending[role]) return;
        try { s.send(JSON.stringify(obj)); } catch (e) { /* 닫히는 중 */ }
    };

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
    const sendIceBurst = (role) => {
        for (let i = 0; i < ICE_BURST; i++) {
            timeouts.push(setTimeout(() => {
                send(role, { type: 'ICE_CANDIDATE', sessionCode, candidate: buildCandidate(Date.now(), __VU * 100 + i) });
                relaySent.add(1);
            }, i * 30));
        }
    };

    const sendDeviceSpec = (role, deviceRole) => {
        send(role, {
            type: 'DEVICE_SPEC', sessionCode, role: deviceRole,
            maxZoom: 10.0, minZoom: 0.5, screenRatio: 1.7778,
            maxResolution: '3840x2160', osType: 'ANDROID',
        });
        relaySent.add(1);
    };

    const sendOffer = () => {
        send('owner', { type: 'OFFER', sessionCode, sdpType: 'offer', sdp: buildSdp(SDP_KB, Date.now()) });
        relaySent.add(1);
        sendIceBurst('owner');
    };

    const startPing = (role) => {
        if (pingStarted[role]) return;
        pingStarted[role] = true;
        intervals.push(setInterval(() => send(role, { type: 'PING' }), PING_SEC * 1000));
    };

    // 공백 중 유실 측정용 프로브: 수립 후 양쪽이 주기적으로 ICE 1건씩 보낸다.
    // 상대가 재접속 중이면 서버가 죽은 소켓으로 중계해 유실된다 → sent−received 로 드러난다.
    const startProbe = () => {
        if (PROBE_SEC <= 0) return;
        intervals.push(setInterval(() => {
            for (const role of ['owner', 'participant']) {
                if (pending[role]) continue; // 보낼 소켓이 없는 쪽은 건너뛴다
                send(role, { type: 'ICE_CANDIDATE', sessionCode, candidate: buildCandidate(Date.now(), __VU * 100 + 99) });
                relaySent.add(1);
            }
        }, PROBE_SEC * 1000));
    };

    // ── 재연결 계약 (#105) ──
    // 예기치 않은 close: 코드·단계를 기록하고, 1012면 즉시 / 그 외엔 backoff+jitter 후 같은 토큰으로 재접속
    const onClose = (role, e) => {
        if (done) return;
        const code = e && e.code != null ? e.code : 0;
        if (pending[role]) {
            // 재접속 시도 자체가 실패(서버 부재 등) — 원래 close 시각은 유지하고 backoff만 키운다
            reconnectFailed.add(1);
        } else {
            unexpectedClose.add(1, { code: String(code), role, phase: established ? 'established' : 'establishing' });
            closeEpoch.add(Date.now());
            if (!RECONNECT || !sessionCode) { signalingErrors.add(1); finish(false); return; }
            const closedAt = Date.now();
            pending[role] = { closedAt };
            // 복원 시한: 최초 close 기준. 넘기면 재접속 실패로 판정
            timeouts.push(setTimeout(() => {
                if (!done && pending[role] && pending[role].closedAt === closedAt) {
                    resumeOk.add(0); signalingErrors.add(1); finish(false);
                }
            }, RESUME_TIMEOUT_MS));
        }
        const attempt = attempts[role]++;
        let delay;
        if (code === 1012 && immediate1012[role] < MAX_IMMEDIATE_1012) {
            // 1012(서버 재시작): 곧바로, 단 0~1s 흩뿌려서 (서버 jitter와 이중 분산).
            // 1차 after 실측: 지연 0으로 재시도하다 드레인 중 인스턴스에 재착지하면 1.2초에 697회 루프가 생겼다
            immediate1012[role]++;
            delay = Math.random() * 1000;
        } else {
            delay = Math.min(1000 * Math.pow(2, attempt) + Math.random() * 1000, MAX_BACKOFF_MS);
        }
        timeouts.push(setTimeout(() => {
            if (done) return;
            // 같은 토큰(=같은 userId)으로 JOIN_SESSION → 서버가 슬롯 takeover 후 SESSION_RESUMED
            connect(role, () => sockets[role].send(JSON.stringify({ type: 'JOIN_SESSION', sessionCode })));
        }, delay));
    };

    const onResumed = (role) => {
        const p = pending[role];
        if (!p) return;
        pending[role] = null;
        attempts[role] = 0;
        immediate1012[role] = 0;
        resumeMs.add(Date.now() - p.closedAt);
        resumeOk.add(1);
        // 계약: P2P 미수립(ANSWER 미수신) 상태로 돌아오면 디렉터(owner)가 OFFER를 다시 보낸다
        if (role === 'owner' && established && !answered) sendOffer();
    };

    // ── 메시지 처리 (역할별 분기, 재접속한 소켓에도 같은 핸들러가 붙는다) ──
    const handle = (role, m) => {
        switch (m.type) {
            case 'SESSION_CREATED':
                createMs.add(Date.now() - t.create);
                sessionCode = m.sessionCode;
                connect('participant', () => {
                    t.join = Date.now();
                    send('participant', { type: 'JOIN_SESSION', sessionCode });
                });
                break;
            case 'PEER_JOINED':
                if (established) break; // 재접속이 takeover가 아닌 신규 입장으로 처리된 경우(슬롯 해제) — 수립 흐름을 다시 타지 않는다
                joinMs.add(Date.now() - t.join);
                established = true;
                // 실제 앱 순서: 스펙 교환 → 오퍼 → ICE, 이후 유휴 유지(PING) → 수명 종료
                sendDeviceSpec('owner', 'DIRECTOR');
                sendOffer();
                startPing('owner');
                startProbe();
                timeouts.push(setTimeout(() => {
                    send('owner', { type: 'END_SESSION', sessionCode });
                    timeouts.push(setTimeout(() => finish(true), 1000)); // 상대의 SESSION_ENDED 수신 여유
                }, sessionLifeMs));
                break;
            case 'OFFER':
                onRelayed(m);
                if (role === 'participant') { // 재발신 OFFER에도 같은 방식으로 응답한다
                    sendDeviceSpec('participant', 'CAMERA');
                    send('participant', { type: 'ANSWER', sessionCode, sdpType: 'answer', sdp: buildSdp(SDP_KB, Date.now()) });
                    relaySent.add(1);
                    sendIceBurst('participant');
                    startPing('participant');
                }
                break;
            case 'ANSWER':
                onRelayed(m);
                if (role === 'owner') answered = true;
                break;
            case 'ICE_CANDIDATE': case 'DEVICE_SPEC':
                onRelayed(m);
                break;
            case 'SESSION_RESUMED':
                onResumed(role);
                break;
            case 'PEER_DISCONNECTED':
                peerDisconnectedSeen.add(1);
                break;
            case 'PEER_RECONNECTED':
                peerReconnectedSeen.add(1);
                // 상대가 돌아왔는데 협상이 안 끝났으면 디렉터가 다시 시작한다
                if (role === 'owner' && established && !answered) sendOffer();
                break;
            case 'SESSION_ENDED':
                finish(true);
                break;
            case 'ERROR':
                if (pending[role]) resumeOk.add(0); // 재접속 JOIN이 거절됨 (세션 소멸 등)
                signalingErrors.add(1);
                finish(false);
                break;
            default: // PONG 등은 흐름에 영향 없음
        }
    };

    // 소켓 생성 + 핸들러 부착. 최초 연결과 재접속이 같은 경로를 쓴다
    function connect(role, onOpen) {
        const sock = new WebSocket(`${WS_URL}?token=${tokens[role]}`);
        sockets[role] = sock;
        sock.onopen = onOpen;
        sock.onmessage = (e) => handle(role, JSON.parse(e.data));
        sock.onerror = () => { /* 연결 실패·오류는 이어지는 close 이벤트에서 한 번만 처리한다 */ };
        sock.onclose = (e) => { if (sockets[role] === sock) onClose(role, e); };
    }

    // ── 시작: owner 소켓 → 세션 생성 ──
    connect('owner', () => {
        t.create = Date.now();
        send('owner', { type: 'CREATE_SESSION' });
    });
}
