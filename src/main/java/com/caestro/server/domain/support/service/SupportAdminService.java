package com.caestro.server.domain.support.service;

import com.caestro.server.domain.fill.store.StoredImage;
import com.caestro.server.domain.support.dto.request.CreateSupportMessageRequest;
import com.caestro.server.domain.support.dto.request.UpdateSupportTicketRequest;
import com.caestro.server.domain.support.dto.response.SupportAdminTicketResponse;
import com.caestro.server.domain.support.dto.response.SupportTicketCountResponse;
import com.caestro.server.domain.support.dto.response.SupportTicketPageResponse;
import com.caestro.server.domain.support.dto.response.SupportTicketSummaryResponse;
import com.caestro.server.domain.support.entity.SupportAttachment;
import com.caestro.server.domain.support.entity.SupportMessage;
import com.caestro.server.domain.support.entity.SupportTicket;
import com.caestro.server.domain.support.enums.SupportAuthor;
import com.caestro.server.domain.support.enums.SupportCategory;
import com.caestro.server.domain.support.enums.SupportStatus;
import com.caestro.server.domain.support.event.SupportRepliedEvent;
import com.caestro.server.domain.support.repository.SupportAttachmentRepository;
import com.caestro.server.domain.support.repository.SupportTicketRepository;
import com.caestro.server.domain.support.store.SupportAttachmentStore;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import io.micrometer.core.instrument.DistributionSummary;
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

/**
 * 앱 문의 — 운영자 쪽 (#263). 관리자 페이지 서버만 부른다(X-Studio-Key 또는 ADMIN).
 * 지표 {@code support.reply.first_hours}(문의 작성부터 첫 답변까지 시간)가 응답 속도의 기록이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupportAdminService {

    static final String METRIC_FIRST_REPLY_HOURS = "support.reply.first_hours";
    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 50;

    private final SupportTicketRepository ticketRepository;
    private final SupportAttachmentRepository attachmentRepository;
    private final SupportAttachmentStore attachmentStore;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @Value("${support.public-base-url:https://api.peakpic.app}")
    private String publicBaseUrl;

    /** 최신순 한 페이지. 미답변만 보려면 {@code status=OPEN}. */
    @Transactional(readOnly = true)
    public SupportTicketPageResponse list(SupportStatus status, SupportCategory category, Long cursor, Integer size) {
        int pageSize = size == null ? DEFAULT_SIZE : Math.max(1, Math.min(size, MAX_SIZE));
        List<SupportTicket> rows = ticketRepository.findPage(status, category, cursor, PageRequest.of(0, pageSize + 1));
        boolean hasNext = rows.size() > pageSize;
        List<SupportTicket> shown = hasNext ? rows.subList(0, pageSize) : rows;
        Long nextCursor = hasNext ? shown.get(shown.size() - 1).getId() : null;
        return new SupportTicketPageResponse(shown.stream().map(SupportTicketSummaryResponse::from).toList(), nextCursor);
    }

    @Transactional(readOnly = true)
    public SupportTicketCountResponse count(SupportStatus status) {
        return new SupportTicketCountResponse(status == null ? ticketRepository.count() : ticketRepository.countByStatus(status));
    }

    /** @throws CustomException SUPPORT_TICKET_NOT_FOUND */
    @Transactional(readOnly = true)
    public SupportAdminTicketResponse get(Long ticketId) {
        return SupportAdminTicketResponse.from(find(ticketId), ticketBaseUrl(ticketId));
    }

    /**
     * 답변. 상태 ANSWERED, 사용자에게 안 읽음 표시, 커밋 뒤 알림함+푸시.
     */
    @Transactional
    public SupportAdminTicketResponse reply(Long ticketId, CreateSupportMessageRequest request) {
        SupportTicket ticket = find(ticketId);
        LocalDateTime now = LocalDateTime.now(clock);
        boolean first = ticket.getFirstAnsweredAt() == null;
        ticket.getMessages().add(SupportMessage.builder().ticket(ticket).author(SupportAuthor.ADMIN).body(request.body().strip()).build());
        ticket.answered(now);
        meterRegistry.counter(SupportService.METRIC_MESSAGE_CREATED, "author", SupportAuthor.ADMIN.name()).increment();
        if (first) {
            DistributionSummary.builder(METRIC_FIRST_REPLY_HOURS).baseUnit("hours").register(meterRegistry)
                    .record(Duration.between(ticket.getCreatedAt(), now).toMinutes() / 60.0);
        }
        eventPublisher.publishEvent(new SupportRepliedEvent(ticket.getId(), ticket.getUser().getId(), ticket.getTitle()));
        log.info("Support ticket replied: id={} first={}", ticket.getId(), first);
        return SupportAdminTicketResponse.from(ticket, ticketBaseUrl(ticketId));
    }

    /** 부분 수정: 닫기·다시 열기·메모. */
    @Transactional
    public SupportAdminTicketResponse update(Long ticketId, UpdateSupportTicketRequest request) {
        SupportTicket ticket = find(ticketId);
        if (request.status() == SupportStatus.CLOSED && ticket.getStatus() != SupportStatus.CLOSED) {
            ticket.close(LocalDateTime.now(clock));
        } else if (request.status() == SupportStatus.OPEN && ticket.getStatus() == SupportStatus.CLOSED) {
            ticket.reopen();
        }
        if (request.adminMemo() != null) {
            ticket.memo(request.adminMemo().isBlank() ? null : request.adminMemo().strip());
        }
        return SupportAdminTicketResponse.from(ticket, ticketBaseUrl(ticketId));
    }

    @Transactional(readOnly = true)
    public Optional<URI> attachmentRedirect(Long ticketId, Long attachmentId) {
        find(ticketId);
        return attachmentStore.redirectUrl(ticketId, findAttachment(ticketId, attachmentId).getStoreId());
    }

    @Transactional(readOnly = true)
    public StoredImage attachment(Long ticketId, Long attachmentId) {
        find(ticketId);
        return attachmentStore.load(ticketId, findAttachment(ticketId, attachmentId).getStoreId());
    }

    private SupportTicket find(Long ticketId) {
        return ticketRepository.findById(ticketId).orElseThrow(() -> new CustomException(ErrorCode.SUPPORT_TICKET_NOT_FOUND));
    }

    private SupportAttachment findAttachment(Long ticketId, Long attachmentId) {
        return attachmentRepository.findByIdAndTicketId(attachmentId, ticketId)
                .orElseThrow(() -> new CustomException(ErrorCode.SUPPORT_ATTACHMENT_NOT_FOUND));
    }

    String ticketBaseUrl(Long ticketId) {
        return publicBaseUrl.replaceAll("/+$", "") + "/admin/support/tickets/" + ticketId;
    }
}
