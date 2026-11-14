package com.caestro.server.domain.push.service;

import com.caestro.server.domain.push.dto.response.PushTestResponse;
import com.caestro.server.domain.push.entity.PushToken;
import com.caestro.server.domain.push.repository.PushTokenRepository;
import com.caestro.server.domain.push.sender.PushMessage;
import com.caestro.server.domain.push.sender.PushResult;
import com.caestro.server.domain.push.sender.PushSender;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 한 사용자의 모든 기기로 알림 하나를 보낸다 (#206). 결과는 {@code push.sent{type,result}} 로 센다.
 * FCM 을 부르는 동안 DB 트랜잭션을 쥐지 않는다(토큰 읽기 → 보내기 → 꺼진 토큰만 짧게 저장). #184 와 같은 이유.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PushService {

    static final String METRIC = "push.sent";

    private final PushTokenRepository tokenRepository;
    private final PushSender sender;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    /**
     * @return FCM 이 받아 준 기기 수
     */
    public int sendToUser(Long userId, PushMessage message) {
        return sendReport(userId, message).sent();
    }

    /** 기기별 결과를 모두 든 보고. 아웃박스 릴레이(#272)가 "다시 보낼지"를 정할 때 쓴다. */
    public record SendReport(int tokens, int sent, int unregistered, int failed) {
        /** 보낼 기기가 하나라도 있었는데 아무 데도 못 보냈고, 그 이유가 죽은 토큰이 아니라 실패였다 → 다시 시도할 가치가 있다. */
        public boolean shouldRetry() {
            return tokens > 0 && sent == 0 && failed > 0;
        }
    }

    public SendReport sendReport(Long userId, PushMessage message) {
        List<PushToken> tokens = tokenRepository.findAllByUserIdAndDisabledAtIsNull(userId);
        int sent = 0;
        int unregistered = 0;
        int failed = 0;
        for (PushToken token : tokens) {
            PushResult result = sender.send(token.getToken(), message);
            meterRegistry.counter(METRIC, "type", message.type(), "result", result.name().toLowerCase()).increment();
            if (result == PushResult.SENT) {
                sent++;
            } else if (result == PushResult.UNREGISTERED) {
                // 앱 삭제·재설치로 죽은 토큰: 끈다. 같은 기기가 다시 등록하면 되살아난다
                token.disable(LocalDateTime.now(clock));
                tokenRepository.save(token);
                unregistered++;
            } else if (result == PushResult.FAILED) {
                failed++;
            }
        }
        return new SendReport(tokens.size(), sent, unregistered, failed);
    }

    /** 본인 기기로 테스트 알림. 앱 연동 확인용. */
    public PushTestResponse sendTest(Long userId) {
        int tokens = tokenRepository.findAllByUserIdAndDisabledAtIsNull(userId).size();
        int sent = sendToUser(userId, PushMessage.test());
        return new PushTestResponse(tokens, sent);
    }
}
