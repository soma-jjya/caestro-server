package com.caestro.server.domain.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.caestro.server.domain.fill.dto.request.CreateFillRequest;
import com.caestro.server.domain.fill.repository.FillSessionRepository;
import com.caestro.server.domain.fill.service.FillService;
import com.caestro.server.domain.push.entity.PushToken;
import com.caestro.server.domain.push.enums.PushPlatform;
import com.caestro.server.domain.push.repository.PushTokenRepository;
import com.caestro.server.domain.push.sender.PushMessage;
import com.caestro.server.domain.push.sender.PushResult;
import com.caestro.server.domain.push.sender.PushSender;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
import com.caestro.server.global.exception.CustomException;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * #206 — 네 칸이 처음 다 찬 순간 주인의 기기로 알림이 "한 번" 간다. 커밋 뒤 비동기라 Mockito timeout 으로 기다린다.
 * 실제 MySQL 필요 (docker-compose up -d / CI 서비스 컨테이너). FCM 은 목.
 */
// RANDOM_PORT: 모의 서블릿 환경에는 WS ServerContainer가 없어 WS 설정 빈이 초기화되지 못한다 (contextLoads와 동일)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FillCompletedPushTest {

    @Autowired private FillService fillService;
    @Autowired private FillSessionRepository sessionRepository;
    @Autowired private PushTokenRepository tokenRepository;
    @Autowired private UserRepository userRepository;

    @MockitoBean private PushSender sender;

    private User owner;
    private User friend;
    private PushToken token;
    private String code;

    private static byte[] jpeg(byte filler) {
        byte[] bytes = new byte[512];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        bytes[3] = (byte) 0xE0;
        for (int i = 4; i < bytes.length; i++) {
            bytes[i] = filler;
        }
        return bytes;
    }

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        // 주인은 게스트여도 된다 — users 행이 있으면 토큰을 묶을 수 있다
        owner = userRepository.save(User.builder().deviceId("push-owner-" + suffix).nickname("주인").role(User.Role.USER).build());
        friend = userRepository.save(User.builder().deviceId("push-friend-" + suffix).nickname("친구").role(User.Role.USER).build());
        token = tokenRepository.save(PushToken.builder().user(owner).platform(PushPlatform.ANDROID)
                .token("tok-" + suffix).lastSeenAt(LocalDateTime.now()).build());
        given(sender.configured()).willReturn(true);
        given(sender.send(anyString(), any())).willReturn(PushResult.SENT);
        code = fillService.create(owner.getId(), new CreateFillRequest("Black", "Strip", null, null, "푸시 테스트", null)).code();
    }

    @AfterEach
    void cleanup() {
        try {
            fillService.delete(code, owner.getId());
        } catch (CustomException ignored) {
            // 이미 지워졌으면 넘어간다
        }
        tokenRepository.delete(token);
        userRepository.delete(owner);
        userRepository.delete(friend);
    }

    @Test
    @DisplayName("네 번째 칸이 차면 주인의 토큰으로 fill_completed 가 한 번 가고, 그 뒤 칸을 바꿔도 다시 가지 않는다")
    void fourthSlot_notifiesOwnerOnce() {
        for (int i = 0; i < 4; i++) {
            fillService.fillSlot(code, i, friend.getId(), "친구", jpeg((byte) i), "image/jpeg");
        }

        ArgumentCaptor<PushMessage> message = ArgumentCaptor.forClass(PushMessage.class);
        verify(sender, timeout(5000).times(1)).send(eq(token.getToken()), message.capture());
        assertThat(message.getValue().type()).isEqualTo(PushMessage.TYPE_FILL_COMPLETED);
        assertThat(message.getValue().data()).containsEntry("code", code);
        assertThat(sessionRepository.findByCode(code).orElseThrow().getCompletedNotifiedAt()).isNotNull();

        // 다섯 번째 변경(칸 교체): 이미 보냈으니 조용하다
        fillService.fillSlot(code, 0, friend.getId(), "친구", jpeg((byte) 9), "image/jpeg");
        verify(sender, after(1500).times(1)).send(anyString(), any());
    }

    @Test
    @DisplayName("세 칸까지는 아무것도 보내지 않는다")
    void threeSlots_noPush() {
        for (int i = 0; i < 3; i++) {
            fillService.fillSlot(code, i, friend.getId(), "친구", jpeg((byte) i), "image/jpeg");
        }
        verify(sender, after(1000).times(0)).send(anyString(), any());
        assertThat(sessionRepository.findByCode(code).orElseThrow().getCompletedNotifiedAt()).isNull();
    }
}
