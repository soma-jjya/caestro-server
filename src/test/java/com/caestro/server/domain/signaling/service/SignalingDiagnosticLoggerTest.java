package com.caestro.server.domain.signaling.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.caestro.server.domain.signaling.service.SignalingDiagnosticLogger.ParsedCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SignalingDiagnosticLoggerTest {

    @Test
    @DisplayName("srflx candidate에서 타입/프로토콜/주소/포트를 파싱한다")
    void parseSrflxCandidate() {
        String candidate = "candidate:2188330982 1 udp 1677729535 115.22.60.18 55507 typ srflx raddr 192.168.0.5 rport 55507";

        ParsedCandidate p = SignalingDiagnosticLogger.parseCandidate(candidate);

        assertEquals("srflx", p.type());
        assertEquals("udp", p.protocol());
        assertEquals("115.22.60.18", p.address());
        assertEquals("55507", p.port());
    }

    @Test
    @DisplayName("candidate: 접두사가 없어도 relay candidate를 파싱한다")
    void parseRelayCandidateWithoutPrefix() {
        String candidate = "2729834545 1 udp 41886207 43.202.225.81 57272 typ relay raddr 115.22.60.18 rport 55507";

        ParsedCandidate p = SignalingDiagnosticLogger.parseCandidate(candidate);

        assertEquals("relay", p.type());
        assertEquals("udp", p.protocol());
        assertEquals("43.202.225.81", p.address());
        assertEquals("57272", p.port());
    }

    @Test
    @DisplayName("tcp host candidate의 프로토콜/타입을 파싱한다")
    void parseHostTcpCandidate() {
        String candidate = "candidate:842163049 1 tcp 1518280447 192.168.0.10 9 typ host tcptype active";

        ParsedCandidate p = SignalingDiagnosticLogger.parseCandidate(candidate);

        assertEquals("host", p.type());
        assertEquals("tcp", p.protocol());
        assertEquals("192.168.0.10", p.address());
    }

    @Test
    @DisplayName("형식이 맞지 않거나 비어있는 입력은 null을 반환한다")
    void returnsNullForInvalidInput() {
        assertNull(SignalingDiagnosticLogger.parseCandidate(null));
        assertNull(SignalingDiagnosticLogger.parseCandidate(""));
        assertNull(SignalingDiagnosticLogger.parseCandidate("   "));
        assertNull(SignalingDiagnosticLogger.parseCandidate("garbage"));
        // "typ" 토큰이 없는 경우
        assertNull(SignalingDiagnosticLogger.parseCandidate("candidate:1 1 udp 100 1.2.3.4 5000 raddr 0.0.0.0"));
    }
}
