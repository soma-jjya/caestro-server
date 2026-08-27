/**
 * 실측 크기에 가까운 WebRTC 페이로드 생성기.
 *
 * 측정용 타임스탬프를 별도 JSON 필드가 아니라 기존 필드(sdp/candidate) "안"에 심는 이유:
 * 서버는 수신 JSON을 SignalingRequest로 역직렬화한 뒤 그대로 재직렬화해 중계하므로
 * (@JsonIgnoreProperties(ignoreUnknown)) 스키마 밖 필드는 중계 과정에서 유실된다.
 */

// ICE candidate: foundation 자리(임의 문자열 허용)를 전송 시각(ms)으로 사용.
// 서버 진단 파서(typ 토큰 탐색)와 호환되는 실제 형식을 유지한다.
export function buildCandidate(ts, seq) {
    const port = 50000 + (seq % 10000);
    return `candidate:${ts} 1 udp 2122260223 192.168.0.${(seq % 200) + 1} ${port} typ host generation 0`;
}

// candidate foundation에서 전송 시각을 복원한다 (13자리 epoch ms)
export function candidateTs(candidate) {
    const m = /^candidate:(\d{13})\s/.exec(candidate || '');
    return m ? Number(m[1]) : null;
}

// SDP: 실제 오퍼 골격 + 측정용 a=x-lt-ts 속성 + 패딩으로 목표 크기(KB)를 맞춘다.
// 기본 4KB(실측 오퍼 수준), 8 초과로 주면 톰캣 인바운드 한도(기본 8KB) 실험이 된다.
export function buildSdp(kb, ts) {
    const header = [
        'v=0',
        `o=- ${ts} 2 IN IP4 127.0.0.1`,
        's=-',
        't=0 0',
        'a=group:BUNDLE 0',
        'a=msid-semantic: WMS',
        'm=video 9 UDP/TLS/RTP/SAVPF 96 97',
        'c=IN IP4 0.0.0.0',
        'a=rtcp:9 IN IP4 0.0.0.0',
        'a=ice-ufrag:ltlt',
        'a=ice-pwd:loadtestloadtestloadtest',
        'a=fingerprint:sha-256 ' + 'AB:'.repeat(31) + 'AB',
        'a=setup:actpass',
        'a=mid:0',
        'a=sendrecv',
        'a=rtcp-mux',
        'a=rtpmap:96 VP8/90000',
        'a=rtpmap:97 rtx/90000',
        'a=fmtp:97 apt=96',
        `a=x-lt-ts:${ts}`,
    ].join('\r\n');

    const target = kb * 1024;
    const pad = 'a=x-lt-pad:' + 'A'.repeat(52); // 한 줄 약 64B
    let sdp = header;
    while (sdp.length < target) sdp += '\r\n' + pad;
    return sdp;
}

// SDP 안의 측정용 타임스탬프를 복원한다
export function sdpTs(sdp) {
    const m = /a=x-lt-ts:(\d{13})/.exec(sdp || '');
    return m ? Number(m[1]) : null;
}
