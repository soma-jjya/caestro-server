package com.caestro.server.domain.fill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.caestro.server.domain.fill.dto.request.CreateFillRequest;
import com.caestro.server.domain.fill.entity.FillSession;
import com.caestro.server.domain.fill.repository.FillSessionRepository;
import com.caestro.server.domain.fill.service.FillExpiringNotifier;
import com.caestro.server.domain.fill.service.FillService;
import com.caestro.server.domain.notification.repository.NotificationRepository;
import com.caestro.server.domain.push.entity.PushToken;
import com.caestro.server.domain.push.enums.PushPlatform;
import com.caestro.server.domain.push.repository.PushTokenRepository;
import com.caestro.server.domain.push.sender.PushMessage;
import com.caestro.server.domain.push.sender.PushResult;
import com.caestro.server.domain.push.sender.PushSender;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.domain.notification.service.OutboxRelay;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * #213 — 만료 24시간 안이고 사진이 있는 세션의 주인에게 "내일 사라져요"가 한 번 간다. 빈 세션과 창 밖 세션은 대상이 아니다.
 * 실제 MySQL 필요 (docker-compose up -d / CI 서비스 컨테이너). FCM 은 목.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// 발송 단언은 릴레이 직접 모드로(#273 스트림 경로는 NotificationStreamTest)
@TestPropertySource(properties = "notification.stream.enabled=false")
class FillExpiringNotifyTest {

    @Autowired private FillService fillService;
    @Autowired private FillSessionRepository sessionRepository;
    @Autowired private FillExpiringNotifier notifier;
    @Autowired private PushTokenRepository tokenRepository;
    @Autowired private NotificationRepository notificationRepository;
    // #272: 발송은 아웃박스 릴레이가 한다. 주기(3초)를 기다리지 않고 한 바퀴 직접 돌린다
    @Autowired private OutboxRelay relay;
    @Autowired private UserRepository userRepository;

    @MockitoBean private PushSender sender;

    private User owner;
    private PushToken token;
    private final List<String> codes = new java.util.ArrayList<>();

    private static byte[] jpeg() {
        byte[] bytes = new byte[512];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        bytes[3] = (byte) 0xE0;
        return bytes;
    }

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        owner = userRepository.save(User.builder().deviceId("exp-owner-" + suffix).nickname("주인").role(User.Role.USER).build());
        token = tokenRepository.save(PushToken.builder().user(owner).platform(PushPlatform.ANDROID)
                .token("tok-exp-" + suffix).lastSeenAt(LocalDateTime.now()).build());
        given(sender.configured()).willReturn(true);
        given(sender.send(anyString(), any())).willReturn(PushResult.SENT);
    }

    @AfterEach
    void cleanup() {
        for (String code : codes) {
            try {
                fillService.delete(code, owner.getId());
            } catch (CustomException ignored) {
                // 이미 지워졌으면 넘어간다
            }
        }
        tokenRepository.delete(token);
        notificationRepository.deleteAll(
                notificationRepository.findByUserIdOrderByIdDesc(owner.getId(), PageRequest.of(0, 20)));
        userRepository.delete(owner);
    }

    /** 세션을 만들고 만료 시각을 지금부터 {@code hoursFromNow} 뒤로 옮긴다. */
    private FillSession sessionExpiringIn(long hoursFromNow, String title, boolean withPhoto) {
        String code = fillService.create(owner.getId(), new CreateFillRequest("Black", "Strip", null, null, title, null)).code();
        codes.add(code);
        if (withPhoto) {
            fillService.fillSlot(code, 0, owner.getId(), "주인", jpeg(), "image/jpeg");
        }
        FillSession session = sessionRepository.findByCode(code).orElseThrow();
        ReflectionTestUtils.setField(session, "expiresAt", LocalDateTime.now().plusHours(hoursFromNow));
        return sessionRepository.save(session);
    }

    @Test
    @DisplayName("24시간 안 만료 + 사진 있음 → 주인에게 1회, 알림함에 1건. 빈 세션과 3일 뒤 만료는 대상이 아니고, 다시 돌려도 더 가지 않는다")
    void notifiesOnceForExpiringSessionsWithPhotos() {
        FillSession target = sessionExpiringIn(10, "제주 여행", true);
        sessionExpiringIn(10, "빈 세션", false);
        sessionExpiringIn(72, "아직 멀었음", true);

        FillExpiringNotifier.Result first = notifier.runOnce(LocalDateTime.now());

        assertThat(first.notified()).isEqualTo(1);
        ArgumentCaptor<PushMessage> message = ArgumentCaptor.forClass(PushMessage.class);
        relay.runOnce(LocalDateTime.now());
        verify(sender, timeout(3000).times(1)).send(eq(token.getToken()), message.capture());
        assertThat(message.getValue().type()).isEqualTo(PushMessage.TYPE_FILL_EXPIRING);
        assertThat(message.getValue().data()).containsEntry("code", target.getCode()).containsKey(PushMessage.DATA_NOTIFICATION_ID);
        assertThat(message.getValue().body()).startsWith("'제주 여행' 사진이");
        assertThat(sessionRepository.findByCode(target.getCode()).orElseThrow().getExpiringNotifiedAt()).isNotNull();
        assertThat(notificationRepository.countByUserIdAndReadAtIsNull(owner.getId())).isEqualTo(1);

        FillExpiringNotifier.Result second = notifier.runOnce(LocalDateTime.now());
        assertThat(second.notified()).isZero();
        relay.runOnce(LocalDateTime.now());
        verify(sender, times(1)).send(anyString(), any());
    }
}
