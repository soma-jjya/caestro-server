package com.caestro.server.domain.inquiry.service;

import com.caestro.server.domain.b2b.repository.B2bProjectRepository;
import com.caestro.server.domain.inquiry.dto.request.CreateInquiryRequest;
import com.caestro.server.domain.inquiry.dto.request.UpdateInquiryRequest;
import com.caestro.server.domain.inquiry.dto.response.InquiryCountResponse;
import com.caestro.server.domain.inquiry.dto.response.InquiryCreatedResponse;
import com.caestro.server.domain.inquiry.dto.response.InquiryPageResponse;
import com.caestro.server.domain.inquiry.dto.response.InquiryResponse;
import com.caestro.server.domain.inquiry.entity.Inquiry;
import com.caestro.server.domain.inquiry.enums.InquiryStatus;
import com.caestro.server.domain.inquiry.enums.InquiryType;
import com.caestro.server.domain.inquiry.event.InquiryReceivedEvent;
import com.caestro.server.domain.inquiry.repository.InquiryRepository;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.caestro.server.global.ratelimit.RedisRateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * B2B 문의 (#216, contracts/b2b-inquiries.md): 광고 랜딩 폼의 접수(인증 없음)와 관리자 페이지의 목록·상세·상태 변경.
 * 지표 {@code inquiry.received{type}} 이 소재별 문의 수, {@code inquiry.rejected{reason}} 이 한도·허니팟에 걸린 수다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InquiryService {

    static final String METRIC_RECEIVED = "inquiry.received";
    static final String METRIC_REJECTED = "inquiry.rejected";
    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 50;
    static final int USER_AGENT_MAX = 300;

    private final InquiryRepository inquiryRepository;
    private final B2bProjectRepository projectRepository;
    private final RedisRateLimiter rateLimiter;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @Value("${inquiry.ratelimit.per-ip:5}")
    private int perIpLimit;

    @Value("${inquiry.ratelimit.per-ip-window-seconds:3600}")
    private long perIpWindowSeconds;

    @Value("${inquiry.ratelimit.daily:300}")
    private int dailyLimit;

    /**
     * 문의 접수. 한도 → 허니팟 → 저장 순서로 본다.
     *
     * @param request   폼 값(Bean Validation 통과한 것)
     * @param clientIp  보낸 쪽 IP(한도 키·흔적)
     * @param userAgent 브라우저 식별자. 300자에서 자른다
     * @return 저장된 id. 허니팟에 걸리면 저장하지 않고 id 0 을 돌려준다(봇에게 실패를 알리지 않는다)
     * @throws CustomException TOO_MANY_REQUESTS - IP 당 또는 전체 한도 초과
     */
    @Transactional
    public InquiryCreatedResponse submit(CreateInquiryRequest request, String clientIp, String userAgent) {
        // 1. 한도: IP 당 창, 그리고 IP 를 바꿔 가며 쏟아 넣어도 테이블이 무한히 크지 않게 전체 하루 창
        if (!rateLimiter.tryAcquire("rl:inquiry:ip:" + clientIp, perIpLimit, Duration.ofSeconds(perIpWindowSeconds))
                || !rateLimiter.tryAcquire("rl:inquiry:all", dailyLimit, Duration.ofDays(1))) {
            meterRegistry.counter(METRIC_REJECTED, "reason", "rate_limit").increment();
            throw new CustomException(ErrorCode.TOO_MANY_REQUESTS);
        }
        // 2. 허니팟: 사람 화면에 없는 칸이 차 있으면 저장하지 않는다
        if (request.isHoneypotFilled()) {
            meterRegistry.counter(METRIC_REJECTED, "reason", "honeypot").increment();
            return new InquiryCreatedResponse(0L);
        }
        // 3. 저장
        CreateInquiryRequest.Utm utm = request.utm();
        Inquiry saved = inquiryRepository.save(Inquiry.builder()
                .type(request.type())
                .organization(request.organization().strip())
                .contactName(request.contactName().strip())
                .contactMethod(request.effectiveContactMethod())
                .phone(blankToNull(request.phone()))
                .email(blankToNull(request.email()))
                .instagram(blankToNull(request.instagram()))
                .region(blankToNull(request.region()))
                .preferredPeriod(blankToNull(request.preferredPeriod()))
                .scale(blankToNull(request.scale()))
                .website(blankToNull(request.website()))
                .message(blankToNull(request.message()))
                .privacyAgreed(true)
                .marketingAgreed(request.marketingConsented())
                .utmSource(utm == null ? null : blankToNull(utm.source()))
                .utmMedium(utm == null ? null : blankToNull(utm.medium()))
                .utmCampaign(utm == null ? null : blankToNull(utm.campaign()))
                .utmContent(utm == null ? null : blankToNull(utm.content()))
                .utmTerm(utm == null ? null : blankToNull(utm.term()))
                .landingPath(blankToNull(request.landingPath()))
                .clientIp(clientIp)
                .userAgent(truncate(userAgent, USER_AGENT_MAX))
                .status(InquiryStatus.NEW)
                .build());
        // 4. 지표와 이벤트(웹훅은 커밋 뒤 비동기)
        meterRegistry.counter(METRIC_RECEIVED, "type", saved.getType().name()).increment();
        eventPublisher.publishEvent(new InquiryReceivedEvent(saved.getId(), saved.getType(), saved.getOrganization(),
                saved.getContactName(), saved.getContactMethod(), saved.contactValue(), saved.getUtmContent()));
        log.info("Inquiry received: id={} type={} contactMethod={} utmContent={}", saved.getId(), saved.getType(),
                saved.getContactMethod(), saved.getUtmContent());
        return new InquiryCreatedResponse(saved.getId());
    }

    /**
     * 최신순 한 페이지.
     *
     * @param status 상태 필터. null 이면 전체
     * @param type   유형 필터. null 이면 전체
     * @param cursor 이전 응답의 nextCursor. null 이면 첫 페이지
     * @param size   null 이면 20, 최대 50
     */
    @Transactional(readOnly = true)
    public InquiryPageResponse list(InquiryStatus status, InquiryType type, Long cursor, Integer size) {
        int pageSize = size == null ? DEFAULT_SIZE : Math.max(1, Math.min(size, MAX_SIZE));
        // 1. 한 건 더 읽어 다음 페이지가 있는지 본다
        List<Inquiry> rows = inquiryRepository.findPage(status, type, cursor, PageRequest.of(0, pageSize + 1));
        // 2. 넘치는 한 건은 잘라 내고, 있었다면 마지막 id 가 다음 커서
        boolean hasNext = rows.size() > pageSize;
        List<Inquiry> shown = hasNext ? rows.subList(0, pageSize) : rows;
        Long nextCursor = hasNext ? shown.get(shown.size() - 1).getId() : null;
        return new InquiryPageResponse(shown.stream().map(InquiryResponse::from).toList(), nextCursor);
    }

    /** @throws CustomException INQUIRY_NOT_FOUND */
    @Transactional(readOnly = true)
    public InquiryResponse get(Long id) {
        return InquiryResponse.from(find(id));
    }

    @Transactional(readOnly = true)
    public InquiryCountResponse count(InquiryStatus status) {
        return new InquiryCountResponse(status == null ? inquiryRepository.count() : inquiryRepository.countByStatus(status));
    }

    /**
     * 관리자의 부분 수정. null 인 필드는 그대로 둔다.
     *
     * @throws CustomException INQUIRY_NOT_FOUND - 없는 문의
     * @throws CustomException B2B_PROJECT_NOT_FOUND - 연결하려는 프로젝트가 없음
     */
    @Transactional
    public InquiryResponse update(Long id, UpdateInquiryRequest request) {
        Inquiry inquiry = find(id);
        // 1. 상태 — NEW 를 처음 벗어나는 순간 handledAt 이 남는다
        if (request.status() != null) {
            inquiry.changeStatus(request.status(), LocalDateTime.now(clock));
        }
        // 2. 메모
        if (request.adminMemo() != null) {
            inquiry.changeMemo(blankToNull(request.adminMemo()));
        }
        // 3. 프로젝트 연결 — 빈 문자열은 해제, 값이 있으면 실존하는 프로젝트여야 한다
        if (request.linkedProjectId() != null) {
            String projectId = blankToNull(request.linkedProjectId());
            if (projectId != null && !projectRepository.existsById(projectId)) {
                throw new CustomException(ErrorCode.B2B_PROJECT_NOT_FOUND);
            }
            inquiry.linkProject(projectId);
        }
        return InquiryResponse.from(inquiry);
    }

    private Inquiry find(Long id) {
        return inquiryRepository.findById(id).orElseThrow(() -> new CustomException(ErrorCode.INQUIRY_NOT_FOUND));
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
