package com.caestro.server.domain.turn.service;

import com.caestro.server.domain.turn.dto.response.TurnCredentialResponse;
import com.caestro.server.domain.turn.dto.response.TurnCredentialResponse.IceServer;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * coturn use-auth-secret(TURN REST API) 방식의 시간제한 임시 자격증명을 발급한다.
 * 백엔드와 coturn이 공유하는 secret으로 HMAC을 생성하며, coturn은 동일 secret으로 재계산·검증한다(상태 비저장).
 * 고정 자격증명 대비 유출 시 자동 만료되어 오픈 릴레이 악용을 방지한다.
 */
@Service
public class TurnCredentialService {

    private static final String HMAC_ALGORITHM = "HmacSHA1";
    private static final int TURN_PORT = 3478;

    private final String secret;
    private final String host;
    private final long ttlSeconds;
    private final Clock clock;

    public TurnCredentialService(
            @Value("${turn.secret}") String secret,
            @Value("${turn.host}") String host,
            @Value("${turn.ttl:3600}") long ttlSeconds,
            Clock clock) {
        this.secret = secret;
        this.host = host;
        this.ttlSeconds = ttlSeconds;
        this.clock = clock;
    }

    /**
     * 임시 자격증명이 포함된 iceServers 설정을 생성한다.
     * username = {만료 unix 시각}:{userId}, credential = base64(HMAC-SHA1(secret, username)).
     *
     * @param userId 인증된 사용자 ID
     * @return STUN(자격증명 없음) + TURN(임시 자격증명 포함)으로 구성된 iceServers 및 ttl
     */
    public TurnCredentialResponse generate(Long userId) {
        // 1. 만료시각 계산 (현재 + TTL) 및 username 구성
        long expiry = clock.instant().getEpochSecond() + ttlSeconds;
        String username = expiry + ":" + userId;

        // 2. secret으로 HMAC 서명하여 credential 생성
        String credential = sign(username);

        // 3. iceServers 구성 (STUN은 인증 불필요, TURN만 자격증명 포함)
        IceServer stun = new IceServer(
                List.of("stun:" + host + ":" + TURN_PORT), null, null);
        IceServer turn = new IceServer(
                List.of("turn:" + host + ":" + TURN_PORT + "?transport=udp"), username, credential);

        return new TurnCredentialResponse(List.of(stun, turn), ttlSeconds);
    }

    /**
     * username을 static-auth-secret으로 HMAC-SHA1 서명 후 Base64로 인코딩한다.
     * coturn(use-auth-secret)이 동일 방식으로 재계산하여 검증한다.
     */
    private String sign(String username) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] raw = mac.doFinal(username.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(raw);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new CustomException(ErrorCode.TURN_CREDENTIAL_FAILED);
        }
    }
}
