package com.caestro.server.domain.notification.service;

import com.caestro.server.domain.notification.dto.response.NotificationPageResponse;
import com.caestro.server.domain.notification.dto.response.NotificationResponse;
import com.caestro.server.domain.notification.dto.response.UnreadCountResponse;
import com.caestro.server.domain.notification.entity.Notification;
import com.caestro.server.domain.notification.repository.NotificationRepository;
import com.caestro.server.domain.push.sender.PushMessage;
import com.caestro.server.domain.user.repository.UserRepository;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림함 (#211, contracts/notifications.md): 푸시를 보내기 전에 먼저 저장하고, 앱이 목록·안 읽은 개수·읽음을 다룬다.
 * 지표는 {@code notification.recorded{type}}(저장) 과 {@code notification.opened{type}}(처음 읽음). push.sent 와 나란히 보면 도달률이 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    static final String METRIC_RECORDED = "notification.recorded";
    static final String METRIC_OPENED = "notification.opened";
    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 50;

    private static final TypeReference<Map<String, String>> DATA_TYPE = new TypeReference<>() {
    };

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    /**
     * 알림 한 줄을 남긴다. 발송 리스너가 FCM 을 부르기 전에 호출하며, 이 메서드의 트랜잭션은 여기서 끝난다(외부 호출은 밖에서).
     *
     * @param userId  받는 사람
     * @param message 보낼 푸시. 제목·본문·data 를 그대로 남긴다
     * @return 저장된 행. id 는 푸시 data 의 notificationId 로 실린다
     */
    @Transactional
    public Notification record(Long userId, PushMessage message) {
        // 1. 저장 (사용자는 참조만 — 조회 없이 FK 만 건다)
        Notification saved = notificationRepository.save(Notification.builder()
                .user(userRepository.getReferenceById(userId))
                .type(message.type())
                .title(message.title())
                .body(message.body())
                .data(encode(message.data()))
                .build());
        // 2. 지표
        meterRegistry.counter(METRIC_RECORDED, "type", message.type()).increment();
        return saved;
    }

    /**
     * 최신순 한 페이지.
     *
     * @param cursor 이전 응답의 nextCursor. null 이면 첫 페이지
     * @param size   null 이면 20, 최대 50
     */
    @Transactional(readOnly = true)
    public NotificationPageResponse list(Long userId, Long cursor, Integer size) {
        int pageSize = size == null ? DEFAULT_SIZE : Math.max(1, Math.min(size, MAX_SIZE));
        // 1. 한 건 더 읽어 다음 페이지가 있는지 본다
        Pageable page = PageRequest.of(0, pageSize + 1);
        List<Notification> rows = cursor == null
                ? notificationRepository.findByUserIdOrderByIdDesc(userId, page)
                : notificationRepository.findByUserIdAndIdLessThanOrderByIdDesc(userId, cursor, page);
        // 2. 넘치는 한 건은 잘라 내고, 있었다면 마지막 id 가 다음 커서
        boolean hasNext = rows.size() > pageSize;
        List<Notification> shown = hasNext ? rows.subList(0, pageSize) : rows;
        List<NotificationResponse> items = new ArrayList<>(shown.size());
        for (Notification n : shown) {
            items.add(NotificationResponse.from(n, decode(n.getData())));
        }
        Long nextCursor = hasNext ? shown.get(shown.size() - 1).getId() : null;
        return new NotificationPageResponse(items, nextCursor);
    }

    @Transactional(readOnly = true)
    public UnreadCountResponse unreadCount(Long userId) {
        return new UnreadCountResponse(notificationRepository.countByUserIdAndReadAtIsNull(userId));
    }

    /**
     * 하나 읽음. 처음 읽을 때만 시각과 지표를 남긴다.
     *
     * @throws CustomException NOTIFICATION_NOT_FOUND - 없거나 남의 알림
     */
    @Transactional
    public void markRead(Long userId, Long id) {
        Notification notification = notificationRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOTIFICATION_NOT_FOUND));
        if (notification.markRead(LocalDateTime.now(clock))) {
            meterRegistry.counter(METRIC_OPENED, "type", notification.getType()).increment();
        }
    }

    /** 모두 읽음. 바뀐 행 수를 돌려준다(지표는 "열었다"가 아니라서 세지 않는다). */
    @Transactional
    public int markAllRead(Long userId) {
        return notificationRepository.markAllRead(userId, LocalDateTime.now(clock));
    }

    /** 보관 기간이 지난 알림 삭제. 배치가 부른다. */
    @Transactional
    public int deleteCreatedBefore(LocalDateTime before) {
        return notificationRepository.deleteCreatedBefore(before);
    }

    private String encode(Map<String, String> data) {
        try {
            return objectMapper.writeValueAsString(data == null ? Map.of() : data);
        } catch (JsonProcessingException e) {
            // data 는 서버가 만든 작은 맵이라 실패할 일이 없지만, 알림 저장이 막히면 안 된다
            log.warn("Notification data not serialised; stored empty: {}", e.toString());
            return "{}";
        }
    }

    private Map<String, String> decode(String json) {
        try {
            return objectMapper.readValue(json == null || json.isBlank() ? "{}" : json, DATA_TYPE);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}
