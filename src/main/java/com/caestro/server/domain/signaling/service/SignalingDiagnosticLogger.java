package com.caestro.server.domain.signaling.service;

import com.caestro.server.domain.signaling.dto.request.SignalingRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * WebRTC 시그널링 메시지(SDP/ICE)를 진단 목적으로 구조화 로깅한다.
 * 서버는 미디어(P2P)를 직접 보지 못하므로, 시그널링으로 오가는 ICE candidate 타입(host/srflx/relay)과
 * SDP 요약을 로그로 남겨 "직결이냐 릴레이냐 / NAT 유형" 진단의 근거를 확보한다.
 * 로깅 실패가 중계(relay)를 막지 않도록 모든 파싱은 예외를 격리한다.
 */
@Slf4j
@Component
public class SignalingDiagnosticLogger {

    private static final String CANDIDATE_PREFIX = "candidate:";

    /**
     * 중계되는 시그널링 메시지를 타입별로 진단 로깅한다.
     * OFFER/ANSWER/ICE_CANDIDATE 만 대상으로 하며, 그 외 타입(DEVICE_SPEC 등)은 무시한다.
     *
     * @param msg         중계 중인 시그널링 메시지
     * @param sessionCode 해당 세션 코드
     * @param direction   메시지 방향 (예: "director->camera")
     */
    public void logRelayed(SignalingRequest msg, String sessionCode, String direction) {
        try {
            switch (msg.type()) {
                case "ICE_CANDIDATE" -> logIceCandidate(msg, sessionCode, direction);
                case "OFFER", "ANSWER" -> logSdp(msg, sessionCode, direction);
                default -> { /* 진단 대상 아님 */ }
            }
        } catch (Exception e) {
            // 진단 로깅 실패가 시그널링 중계를 막지 않도록 예외를 격리
            log.debug("Signaling diagnostic logging failed: type={}", msg.type(), e);
        }
    }

    /**
     * ICE_CANDIDATE 메시지의 candidate 문자열을 파싱해 타입/프로토콜/주소를 로깅한다.
     * 빈 candidate(트리클 ICE 종료 신호)는 end-of-candidates로 기록한다.
     */
    private void logIceCandidate(SignalingRequest msg, String sessionCode, String direction) {
        String candidate = msg.candidate();
        if (candidate == null || candidate.isBlank()) {
            log.info("[ICE] session={} dir={} end-of-candidates", sessionCode, direction);
            return;
        }
        ParsedCandidate p = parseCandidate(candidate);
        if (p == null) {
            log.info("[ICE] session={} dir={} candidate=(unparseable)", sessionCode, direction);
            return;
        }
        log.info("[ICE] session={} dir={} candType={} proto={} addr={}:{}",
                sessionCode, direction, p.type(), p.protocol(), p.address(), p.port());
    }

    /**
     * OFFER/ANSWER 메시지의 SDP를 요약 로깅한다.
     * SDP 원문은 남기지 않고(용량·개인정보), 미디어 라인 수와 내장 candidate 수만 기록한다.
     */
    private void logSdp(SignalingRequest msg, String sessionCode, String direction) {
        String sdp = msg.sdp();
        int sdpLen = sdp == null ? 0 : sdp.length();
        int mLines = countOccurrences(sdp, "\nm=");
        int embeddedCandidates = countOccurrences(sdp, "a=candidate");
        log.info("[SDP] session={} dir={} sdpType={} sdpLen={} mLines={} embeddedCandidates={}",
                sessionCode, direction, msg.sdpType(), sdpLen, mLines, embeddedCandidates);
    }

    /**
     * ICE candidate 문자열을 파싱해 타입/프로토콜/주소/포트를 추출한다.
     * 예: "candidate:842163049 1 udp 1677729535 115.22.60.18 55507 typ srflx raddr ..."
     * 형식(SDP candidate-attribute): foundation component protocol priority address port "typ" cand-type ...
     *
     * @param candidate 원본 candidate 문자열 ("candidate:" 접두사 유무 무관)
     * @return 파싱 결과 (형식이 예상과 다르면 null)
     */
    public static ParsedCandidate parseCandidate(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        // "candidate:" 접두사가 붙어 오는 경우와 아닌 경우 모두 대응
        String body = candidate.startsWith(CANDIDATE_PREFIX)
                ? candidate.substring(CANDIDATE_PREFIX.length())
                : candidate;
        String[] t = body.trim().split("\\s+");
        // 최소 필드: foundation(0) component(1) protocol(2) priority(3) address(4) port(5) typ(6) type(7)
        if (t.length < 8) {
            return null;
        }

        String protocol = t[2];
        String address = t[4];
        String port = t[5];

        // "typ" 토큰 다음이 candidate 타입 (host/srflx/prflx/relay)
        String type = null;
        for (int i = 6; i < t.length - 1; i++) {
            if ("typ".equals(t[i])) {
                type = t[i + 1];
                break;
            }
        }
        if (type == null) {
            return null;
        }
        return new ParsedCandidate(type, protocol, address, port);
    }

    /**
     * 문자열 내 특정 부분 문자열의 출현 횟수를 센다.
     */
    private static int countOccurrences(String s, String sub) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = s.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    /**
     * 파싱된 ICE candidate 정보.
     */
    public record ParsedCandidate(String type, String protocol, String address, String port) {
    }
}
