package com.caestro.server.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.caestro.server.domain.notification.dto.response.NotificationPageResponse;
import com.caestro.server.domain.notification.entity.Notification;
import com.caestro.server.domain.notification.repository.NotificationRepository;
import com.caestro.server.domain.push.sender.PushMessage;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/** 알림함 규칙(#211): 저장은 푸시 내용 그대로, 목록은 한 건 더 읽어 커서를 정하고, 읽음은 처음 한 번만 센다. */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-02T03:00:00Z");

    @Mock private NotificationRepository notificationRepository;
    @Mock private UserRepository userRepository;

    private SimpleMeterRegistry registry;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        service = new NotificationService(notificationRepository, userRepository, new ObjectMapper(), registry,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Notification row(long id, String type) {
        Notification n = Notification.builder().user(User.builder().deviceId("d").role(User.Role.USER).build())
                .type(type).title("t").body("b").data("{\"code\":\"K7X2MQ\"}").build();
        ReflectionTestUtils.setField(n, "id", id);
        return n;
    }

    @Test
    @DisplayName("저장: 푸시의 type·제목·본문·data 가 그대로 남고 notification.recorded 가 1 오른다")
    void record_keepsThePushAsIs() {
        given(userRepository.getReferenceById(7L)).willReturn(User.builder().deviceId("d").role(User.Role.USER).build());
        given(notificationRepository.save(any(Notification.class))).willAnswer(inv -> inv.getArgument(0));

        Notification saved = service.record(7L, PushMessage.fillCompleted("K7X2MQ"));

        assertThat(saved.getType()).isEqualTo(PushMessage.TYPE_FILL_COMPLETED);
        assertThat(saved.getTitle()).isEqualTo("네컷이 완성됐어요");
        assertThat(saved.getData()).isEqualTo("{\"code\":\"K7X2MQ\"}");
        assertThat(registry.counter(NotificationService.METRIC_RECORDED, "type", "fill_completed").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("목록: 한 건 더 읽어 다음 페이지가 있으면 마지막 id 가 커서, 없으면 null. data 는 맵으로 풀린다")
    void list_pagesByCursor() {
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        List<Notification> threeRows = LongStream.of(30, 20, 10).mapToObj(id -> row(id, "fill_completed")).toList();
        given(notificationRepository.findByUserIdOrderByIdDesc(eq(7L), page.capture())).willReturn(threeRows);

        NotificationPageResponse first = service.list(7L, null, 2);

        assertThat(page.getValue().getPageSize()).isEqualTo(3);
        assertThat(first.items()).extracting("id").containsExactly(30L, 20L);
        assertThat(first.nextCursor()).isEqualTo(20L);
        assertThat(first.items().get(0).data()).containsEntry("code", "K7X2MQ");

        given(notificationRepository.findByUserIdAndIdLessThanOrderByIdDesc(eq(7L), eq(20L), any()))
                .willReturn(List.of(row(10, "fill_completed")));
        NotificationPageResponse last = service.list(7L, 20L, 2);
        assertThat(last.items()).hasSize(1);
        assertThat(last.nextCursor()).isNull();
    }

    @Test
    @DisplayName("읽음: 처음 한 번만 readAt 과 notification.opened 를 남기고, 남의 알림은 404")
    void markRead_onceAndOnlyMine() {
        Notification mine = row(1, "fill_completed");
        given(notificationRepository.findByIdAndUserId(1L, 7L)).willReturn(Optional.of(mine));

        service.markRead(7L, 1L);
        service.markRead(7L, 1L);

        assertThat(mine.getReadAt()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThat(registry.counter(NotificationService.METRIC_OPENED, "type", "fill_completed").count()).isEqualTo(1.0);

        given(notificationRepository.findByIdAndUserId(1L, 8L)).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.markRead(8L, 1L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND);
    }

    @Test
    @DisplayName("모두 읽음은 지금 시각으로 한 번에 바꾸고, 보관 정리는 기준 시각 이전 행을 지운다")
    void markAllReadAndCleanup() {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
        given(notificationRepository.markAllRead(7L, now)).willReturn(3);
        assertThat(service.markAllRead(7L)).isEqualTo(3);

        service.deleteCreatedBefore(now.minusDays(90));
        verify(notificationRepository).deleteCreatedBefore(now.minusDays(90));
    }
}
