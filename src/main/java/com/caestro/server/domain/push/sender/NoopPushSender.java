package com.caestro.server.domain.push.sender;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** push.enabled 가 false(기본)일 때: 아무것도 보내지 않고 로그만. 로컬·CI 가 FCM 키 없이 돈다. */
@Slf4j
@Component
@ConditionalOnProperty(name = "push.enabled", havingValue = "false", matchIfMissing = true)
public class NoopPushSender implements PushSender {

    @Override
    public PushResult send(String token, PushMessage message) {
        log.info("Push disabled (push.enabled=false): type={} title={}", message.type(), message.title());
        return PushResult.DISABLED;
    }

    @Override
    public boolean configured() {
        return false;
    }
}
