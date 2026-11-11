package com.caestro.server.domain.support.listener;

import com.caestro.server.domain.support.enums.SupportCategory;
import com.caestro.server.domain.support.event.SupportMessageCreatedEvent;
import com.caestro.server.domain.support.event.SupportTicketCreatedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 채널 한 줄의 모양. 글 내용·닉네임은 들어가지 않고, URL 이 비면 꺼진다. */
class SupportWebhookNotifierTest {

    @Test
    @DisplayName("새 문의: [앱 문의 #id] 분류 · 제목 · 플랫폼 앱버전 + 관리자 링크. 기기 정보가 없으면 그 조각이 빠진다")
    void renderCreated() {
        SupportWebhookNotifier notifier = new SupportWebhookNotifier("https://hook.example/x", "https://peakpic.app/admin/support/", null, new SimpleMeterRegistry());

        assertThat(notifier.renderCreated(new SupportTicketCreatedEvent(12L, SupportCategory.BUG, "사진이 안 올라가요", "ANDROID", "1.4.2")))
                .isEqualTo("[앱 문의 #12] BUG · 사진이 안 올라가요 · ANDROID 1.4.2\nhttps://peakpic.app/admin/support/12");
        assertThat(notifier.renderCreated(new SupportTicketCreatedEvent(13L, SupportCategory.OTHER, "제목", null, null)))
                .isEqualTo("[앱 문의 #13] OTHER · 제목\nhttps://peakpic.app/admin/support/13");
        assertThat(notifier.renderMessage(new SupportMessageCreatedEvent(12L, "사진이 안 올라가요")))
                .isEqualTo("[앱 문의 #12] 이어 쓰기 · 사진이 안 올라가요\nhttps://peakpic.app/admin/support/12");
        assertThat(notifier.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("웹훅 URL 이 비면 꺼진 상태이고 이벤트를 받아도 아무것도 보내지 않는다(클라이언트가 없어도 예외 없음)")
    void disabledWhenNoUrl() {
        SupportWebhookNotifier notifier = new SupportWebhookNotifier(" ", "", null, new SimpleMeterRegistry());

        assertThat(notifier.isEnabled()).isFalse();
        notifier.onCreated(new SupportTicketCreatedEvent(1L, SupportCategory.BUG, "t", null, null));
        notifier.onMessage(new SupportMessageCreatedEvent(1L, "t"));
    }
}
