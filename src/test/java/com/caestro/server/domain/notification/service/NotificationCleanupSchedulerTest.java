package com.caestro.server.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationCleanupSchedulerTest {

    @Mock private NotificationService notificationService;

    @Test
    @DisplayName("보관 기간(일)만큼 뺀 시각 이전의 알림을 지우고 그 수를 돌려준다")
    void deletesOlderThanRetention() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 2, 5, 20);
        given(notificationService.deleteCreatedBefore(now.minusDays(90))).willReturn(12);

        NotificationCleanupScheduler scheduler =
                new NotificationCleanupScheduler(notificationService, Clock.systemUTC().withZone(ZoneOffset.UTC), 90);

        assertThat(scheduler.runOnce(now)).isEqualTo(12);
    }
}
