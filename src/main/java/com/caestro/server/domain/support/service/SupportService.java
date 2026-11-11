package com.caestro.server.domain.support.service;

import com.caestro.server.domain.fill.store.FillImageTypes;
import com.caestro.server.domain.fill.store.StoredImage;
import com.caestro.server.domain.support.dto.request.CreateSupportMessageRequest;
import com.caestro.server.domain.support.dto.request.CreateSupportTicketRequest;
import com.caestro.server.domain.support.dto.response.SupportAttachmentResponse;
import com.caestro.server.domain.support.dto.response.SupportTicketPageResponse;
import com.caestro.server.domain.support.dto.response.SupportTicketResponse;
import com.caestro.server.domain.support.dto.response.SupportTicketSummaryResponse;
import com.caestro.server.domain.support.entity.SupportAttachment;
import com.caestro.server.domain.support.entity.SupportMessage;
import com.caestro.server.domain.support.entity.SupportTicket;
import com.caestro.server.domain.support.enums.SupportAuthor;
import com.caestro.server.domain.support.enums.SupportStatus;
import com.caestro.server.domain.support.event.SupportMessageCreatedEvent;
import com.caestro.server.domain.support.event.SupportTicketCreatedEvent;
import com.caestro.server.domain.support.repository.SupportAttachmentRepository;
import com.caestro.server.domain.support.repository.SupportTicketRepository;
import com.caestro.server.domain.support.store.SupportAttachmentStore;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.caestro.server.global.ratelimit.RedisRateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 앱 문의 — 사용자 쪽 (#263, contracts/support.md). 로그인 계정만 쓸 수 있고 본인 문의만 보인다.
 * 지표 {@code support.ticket.created{category}}, {@code support.message.created{author}}, {@code support.rejected{reason}}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupportService {

    static final String METRIC_TICKET_CREATED = "support.ticket.created";
    static final String METRIC_MESSAGE_CREATED = "support.message.created";
    static final String METRIC_REJECTED = "support.rejected";
    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 50;
    static final Set<String> ALLOWED_IMAGE_TYPES = Set.of("image/jpeg", "image/png");

    private final SupportTicketRepository ticketRepository;
    private final SupportAttachmentRepository attachmentRepository;
    private final SupportAttachmentStore attachmentStore;
    private final UserRepository userRepository;
    private final RedisRateLimiter rateLimiter;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @Value("${support.max-attachments:5}")
    private int maxAttachments;

    @Value("${support.max-attachment-bytes:5242880}")
    private int maxAttachmentBytes;

    @Value("${support.ratelimit.create-daily:5}")
    private int createDailyLimit;

    @Value("${support.ratelimit.message-daily:30}")
    private int messageDailyLimit;

    @Value("${support.public-base-url:https://api.peakpic.app}")
    private String publicBaseUrl;

    /**
     * 문의 작성. 첫 메시지까지 한 트랜잭션에 저장한다.
     *
     * @throws CustomException SUPPORT_LOGIN_REQUIRED - 게스트 또는 탈퇴 계정
     * @throws CustomException TOO_MANY_REQUESTS - 하루 작성 한도 초과
     */
    @Transactional
    public SupportTicketResponse create(Long userId, CreateSupportTicketRequest request) {
        // 1. 로그인 계정인지
        User user = loginUser(userId);
        // 2. 한도: 사용자당 하루 고정 창
        if (!rateLimiter.tryAcquire("rl:support:create:" + userId, createDailyLimit, Duration.ofDays(1))) {
            meterRegistry.counter(METRIC_REJECTED, "reason", "create_limit").increment();
            throw new CustomException(ErrorCode.TOO_MANY_REQUESTS);
        }
        // 3. 티켓 + 첫 메시지
        LocalDateTime now = LocalDateTime.now(clock);
        SupportTicket ticket = SupportTicket.builder()
                .user(user)
                .category(request.category())
                .title(request.title().strip())
                .status(SupportStatus.OPEN)
                .appVersion(blankToNull(request.appVersion()))
                .platform(blankToNull(request.platform()))
                .osVersion(blankToNull(request.osVersion()))
                .deviceModel(blankToNull(request.deviceModel()))
                .userUnread(false)
                .lastMessageAt(now)
                .build();
        ticket.getMessages().add(SupportMessage.builder().ticket(ticket).author(SupportAuthor.USER).body(request.body().strip()).build());
        SupportTicket saved = ticketRepository.save(ticket);
        // 4. 지표와 이벤트(웹훅은 커밋 뒤)
        meterRegistry.counter(METRIC_TICKET_CREATED, "category", saved.getCategory().name()).increment();
        meterRegistry.counter(METRIC_MESSAGE_CREATED, "author", SupportAuthor.USER.name()).increment();
        eventPublisher.publishEvent(new SupportTicketCreatedEvent(saved.getId(), saved.getCategory(), saved.getTitle(),
                saved.getPlatform(), saved.getAppVersion()));
        log.info("Support ticket created: id={} user={} category={} platform={} appVersion={}",
                saved.getId(), userId, saved.getCategory(), saved.getPlatform(), saved.getAppVersion());
        return SupportTicketResponse.from(saved, ticketBaseUrl(saved.getId()));
    }

    /** 내 문의 최신순 한 페이지. */
    @Transactional(readOnly = true)
    public SupportTicketPageResponse list(Long userId, Long cursor, Integer size) {
        int pageSize = size == null ? DEFAULT_SIZE : Math.max(1, Math.min(size, MAX_SIZE));
        List<SupportTicket> rows = ticketRepository.findPageByUser(userId, cursor, PageRequest.of(0, pageSize + 1));
        boolean hasNext = rows.size() > pageSize;
        List<SupportTicket> shown = hasNext ? rows.subList(0, pageSize) : rows;
        Long nextCursor = hasNext ? shown.get(shown.size() - 1).getId() : null;
        return new SupportTicketPageResponse(shown.stream().map(SupportTicketSummaryResponse::from).toList(), nextCursor);
    }

    /**
     * 상세. 열면 "안 읽은 답변" 표시가 사라진다 — 그래서 쓰기 트랜잭션이다.
     *
     * @throws CustomException SUPPORT_TICKET_NOT_FOUND - 없거나 남의 문의
     */
    @Transactional
    public SupportTicketResponse get(Long userId, Long ticketId) {
        SupportTicket ticket = mine(userId, ticketId);
        if (ticket.isUserUnread()) {
            ticket.markReadByUser();
        }
        return SupportTicketResponse.from(ticket, ticketBaseUrl(ticketId));
    }

    /**
     * 이어 쓰기. 닫힌 문의도 다시 열린다(운영자 차례).
     *
     * @throws CustomException TOO_MANY_REQUESTS - 하루 메시지 한도 초과
     */
    @Transactional
    public SupportTicketResponse addMessage(Long userId, Long ticketId, CreateSupportMessageRequest request) {
        SupportTicket ticket = mine(userId, ticketId);
        if (!rateLimiter.tryAcquire("rl:support:message:" + userId, messageDailyLimit, Duration.ofDays(1))) {
            meterRegistry.counter(METRIC_REJECTED, "reason", "message_limit").increment();
            throw new CustomException(ErrorCode.TOO_MANY_REQUESTS);
        }
        ticket.getMessages().add(SupportMessage.builder().ticket(ticket).author(SupportAuthor.USER).body(request.body().strip()).build());
        ticket.touchByUser(LocalDateTime.now(clock));
        meterRegistry.counter(METRIC_MESSAGE_CREATED, "author", SupportAuthor.USER.name()).increment();
        eventPublisher.publishEvent(new SupportMessageCreatedEvent(ticket.getId(), ticket.getTitle()));
        return SupportTicketResponse.from(ticket, ticketBaseUrl(ticketId));
    }

    /**
     * 사진 한 장 추가. 헤더 대신 앞 바이트로 형식을 판정한다.
     *
     * @throws CustomException SUPPORT_ATTACHMENT_LIMIT - 이미 최대 장수
     * @throws CustomException SUPPORT_ATTACHMENT_TOO_LARGE - 장당 상한 초과
     * @throws CustomException FILL_INVALID_IMAGE - JPEG/PNG 가 아님
     */
    @Transactional
    public SupportAttachmentResponse addAttachment(Long userId, Long ticketId, byte[] body, String contentType) {
        SupportTicket ticket = mine(userId, ticketId);
        if (attachmentRepository.countByTicketId(ticketId) >= maxAttachments) {
            throw new CustomException(ErrorCode.SUPPORT_ATTACHMENT_LIMIT);
        }
        if (body == null || body.length == 0) {
            throw new CustomException(ErrorCode.FILL_INVALID_IMAGE);
        }
        if (body.length > maxAttachmentBytes) {
            throw new CustomException(ErrorCode.SUPPORT_ATTACHMENT_TOO_LARGE);
        }
        String type = FillImageTypes.resolve(body, contentType, ALLOWED_IMAGE_TYPES);
        String storeId = attachmentStore.store(ticketId, type, body);
        SupportAttachment saved = attachmentRepository.save(SupportAttachment.builder()
                .ticket(ticket).storeId(storeId).contentType(type).sizeBytes(body.length).build());
        return SupportAttachmentResponse.from(saved, ticketBaseUrl(ticketId));
    }

    /** 사진 삭제(본인 문의). 바이트는 행을 지운 뒤 지운다 — 바이트 삭제가 실패해도 행이 남아 가리키는 일이 없게. */
    @Transactional
    public void deleteAttachment(Long userId, Long ticketId, Long attachmentId) {
        mine(userId, ticketId);
        SupportAttachment attachment = attachmentRepository.findByIdAndTicketId(attachmentId, ticketId)
                .orElseThrow(() -> new CustomException(ErrorCode.SUPPORT_ATTACHMENT_NOT_FOUND));
        attachmentRepository.delete(attachment);
        attachmentStore.delete(ticketId, attachment.getStoreId());
    }

    /** 사진 조회: 저장소가 직접 내줄 수 있으면(S3) 서명 주소. */
    @Transactional(readOnly = true)
    public Optional<URI> attachmentRedirect(Long userId, Long ticketId, Long attachmentId) {
        mine(userId, ticketId);
        return attachmentStore.redirectUrl(ticketId, findAttachment(ticketId, attachmentId).getStoreId());
    }

    /** 사진 바이트 중계(RDS). */
    @Transactional(readOnly = true)
    public StoredImage attachment(Long userId, Long ticketId, Long attachmentId) {
        mine(userId, ticketId);
        return attachmentStore.load(ticketId, findAttachment(ticketId, attachmentId).getStoreId());
    }

    /** 문의와 그 사진을 전부 지운다(보관 기간 정리). 바이트 먼저, 행은 cascade. */
    @Transactional
    public void purge(SupportTicket ticket) {
        attachmentStore.deleteByTicket(ticket.getId());
        ticketRepository.delete(ticket);
    }

    private User loginUser(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        if (user.isDeleted() || user.isGuest()) {
            meterRegistry.counter(METRIC_REJECTED, "reason", "guest").increment();
            throw new CustomException(ErrorCode.SUPPORT_LOGIN_REQUIRED);
        }
        return user;
    }

    private SupportTicket mine(Long userId, Long ticketId) {
        return ticketRepository.findByIdAndUserId(ticketId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.SUPPORT_TICKET_NOT_FOUND));
    }

    private SupportAttachment findAttachment(Long ticketId, Long attachmentId) {
        return attachmentRepository.findByIdAndTicketId(attachmentId, ticketId)
                .orElseThrow(() -> new CustomException(ErrorCode.SUPPORT_ATTACHMENT_NOT_FOUND));
    }

    String ticketBaseUrl(Long ticketId) {
        return publicBaseUrl.replaceAll("/+$", "") + "/support/tickets/" + ticketId;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
