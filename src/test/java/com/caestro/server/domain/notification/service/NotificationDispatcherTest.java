package com.caestro.server.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.caestro.server.domain.notification.entity.Notification;
import com.caestro.server.domain.push.sender.PushMessage;
import com.caestro.server.domain.push.service.PushService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** 알림 한 길(#211, #213): 저장 → id 실어 푸시. 저장이 막혀도 푸시는 간다. */
@ExtendWith(MockitoExtension.class)
class NotificationDispatcherTest {

    @Mock private NotificationService notificationService;
    @Mock private PushService pushService;
    @InjectMocks private NotificationDispatcher dispatcher;

    @Test
    @DisplayName("저장된 행의 id 가 푸시 data 의 notificationId 로 실린다")
    void recordsThenSendsWithId() {
        Notification row = Notification.builder().type("fill_expiring").title("t").body("b").data("{}").build();
        ReflectionTestUtils.setField(row, "id", 42L);
        PushMessage message = PushMessage.fillExpiring("K7X2MQ", "제주");
        given(notificationService.record(7L, message)).willReturn(row);
        given(pushService.sendToUser(eq(7L), any())).willReturn(1);

        assertThat(dispatcher.notify(7L, message)).isEqualTo(1);

        ArgumentCaptor<PushMessage> sent = ArgumentCaptor.forClass(PushMessage.class);
        verify(pushService).sendToUser(eq(7L), sent.capture());
        assertThat(sent.getValue().data()).containsEntry("code", "K7X2MQ").containsEntry("notificationId", "42");
        assertThat(sent.getValue().title()).isEqualTo("네컷 사진이 곧 만료돼요!");
    }

    @Test
    @DisplayName("알림함 저장이 실패하면 id 없이 푸시는 그대로 간다")
    void inboxFailureDoesNotBlockPush() {
        PushMessage message = PushMessage.fillCompleted("K7X2MQ");
        given(notificationService.record(7L, message)).willThrow(new IllegalStateException("db down"));
        given(pushService.sendToUser(7L, message)).willReturn(2);

        assertThat(dispatcher.notify(7L, message)).isEqualTo(2);
        verify(pushService).sendToUser(7L, message);
    }
}
