package com.caestro.server.domain.support;

import com.caestro.server.domain.fill.service.FillCodeGenerator;
import com.caestro.server.domain.fill.store.StoredImage;
import com.caestro.server.domain.support.dto.request.CreateSupportMessageRequest;
import com.caestro.server.domain.support.dto.request.CreateSupportTicketRequest;
import com.caestro.server.domain.support.dto.request.UpdateSupportTicketRequest;
import com.caestro.server.domain.support.dto.response.SupportAdminTicketResponse;
import com.caestro.server.domain.support.dto.response.SupportAttachmentResponse;
import com.caestro.server.domain.support.dto.response.SupportTicketPageResponse;
import com.caestro.server.domain.support.dto.response.SupportTicketResponse;
import com.caestro.server.domain.support.entity.SupportTicket;
import com.caestro.server.domain.support.enums.SupportAuthor;
import com.caestro.server.domain.support.enums.SupportCategory;
import com.caestro.server.domain.support.enums.SupportStatus;
import com.caestro.server.domain.support.event.SupportMessageCreatedEvent;
import com.caestro.server.domain.support.event.SupportRepliedEvent;
import com.caestro.server.domain.support.event.SupportTicketCreatedEvent;
import com.caestro.server.domain.support.repository.SupportAttachmentBlobRepository;
import com.caestro.server.domain.support.repository.SupportAttachmentRepository;
import com.caestro.server.domain.support.repository.SupportTicketRepository;
import com.caestro.server.domain.support.service.SupportAdminService;
import com.caestro.server.domain.support.service.SupportCleanupScheduler;
import com.caestro.server.domain.support.service.SupportService;
import com.caestro.server.domain.support.store.RdsSupportAttachmentStore;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.caestro.server.global.ratelimit.RedisRateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/** 실제 쿼리로 문의 작성·본인 격리·커서 페이지·답변/이어 쓰기 상태·사진 5장·보관 정리를 검증한다 (H2, RDS 저장소). */
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.show-sql=false",
        "support.max-attachment-bytes=65536"}, showSql = false)
@ContextConfiguration(classes = SupportPersistenceTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SupportPersistenceTest {

    static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
    static final LocalDateTime NOW_LDT = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    @Configuration(proxyBeanMethods = false)
    @EnableJpaAuditing
    @EntityScan(basePackageClasses = {SupportTicket.class, User.class})
    @EnableJpaRepositories(basePackageClasses = {SupportTicketRepository.class, UserRepository.class})
    @Import({SupportService.class, SupportAdminService.class, SupportCleanupScheduler.class,
            RdsSupportAttachmentStore.class, FillCodeGenerator.class, Events.class})
    static class Config {
        @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean MeterRegistry meterRegistry() { return new SimpleMeterRegistry(); }
    }

    /** 서비스가 올린 이벤트를 모아 둔다(웹훅·푸시 리스너 대신). */
    @Component
    static class Events {
        final List<SupportTicketCreatedEvent> created = new ArrayList<>();
        final List<SupportMessageCreatedEvent> messages = new ArrayList<>();
        final List<SupportRepliedEvent> replied = new ArrayList<>();
        @EventListener void on(SupportTicketCreatedEvent e) { created.add(e); }
        @EventListener void on(SupportMessageCreatedEvent e) { messages.add(e); }
        @EventListener void on(SupportRepliedEvent e) { replied.add(e); }
        void clear() { created.clear(); messages.clear(); replied.clear(); }
    }

    @Autowired SupportService service;
    @Autowired SupportAdminService admin;
    @Autowired SupportCleanupScheduler cleanup;
    @Autowired SupportTicketRepository tickets;
    @Autowired SupportAttachmentRepository attachments;
    @Autowired SupportAttachmentBlobRepository blobs;
    @Autowired UserRepository users;
    @Autowired Events events;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean RedisRateLimiter rateLimiter;

    Long me;
    Long other;
    Long guest;

    @BeforeEach
    void setUp() {
        tickets.deleteAll();
        blobs.deleteAll();
        events.clear();
        given(rateLimiter.tryAcquire(anyString(), anyInt(), any())).willReturn(true);
        String suffix = Long.toString(System.nanoTime());
        me = users.save(User.builder().oauthProvider("kakao").oauthId("me-" + suffix).nickname("나").role(User.Role.USER).build()).getId();
        other = users.save(User.builder().oauthProvider("google").oauthId("other-" + suffix).nickname("남").role(User.Role.USER).build()).getId();
        guest = users.save(User.builder().deviceId("guest-" + suffix).role(User.Role.USER).build()).getId();
    }

    @Test
    @DisplayName("작성: 티켓 OPEN + 첫 메시지 USER, 기기 정보와 시각이 남고, 커밋 뒤 웹훅용 이벤트에 글 내용은 없다")
    void create() {
        SupportTicketResponse res = service.create(me, request("사진이 안 올라가요", "갤러리에서 고르면 멈춰요"));

        assertThat(res.status()).isEqualTo(SupportStatus.OPEN);
        assertThat(res.userUnread()).isFalse();
        assertThat(res.lastMessageAt()).isEqualTo(NOW_LDT);
        assertThat(res.messages()).hasSize(1);
        assertThat(res.messages().get(0).author()).isEqualTo(SupportAuthor.USER);
        assertThat(res.messages().get(0).body()).isEqualTo("갤러리에서 고르면 멈춰요");
        assertThat(res.platform()).isEqualTo("ANDROID");
        assertThat(res.deviceModel()).isEqualTo("SM-S928N");
        assertThat(res.attachments()).isEmpty();
        assertThat(events.created).singleElement().satisfies(e -> {
            assertThat(e.ticketId()).isEqualTo(res.id());
            assertThat(e.category()).isEqualTo(SupportCategory.BUG);
            assertThat(e.title()).isEqualTo("사진이 안 올라가요");
            assertThat(e.appVersion()).isEqualTo("1.4.2");
        });
    }

    @Test
    @DisplayName("게스트와 탈퇴 계정은 403 SUPPORT_LOGIN_REQUIRED, 아무것도 저장되지 않는다")
    void guestCannotCreate() {
        assertThatThrownBy(() -> service.create(guest, request("a", "b")))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.SUPPORT_LOGIN_REQUIRED);

        User withdrawn = users.findById(other).orElseThrow();
        withdrawn.withdraw(NOW_LDT);
        users.save(withdrawn);
        assertThatThrownBy(() -> service.create(other, request("a", "b")))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.SUPPORT_LOGIN_REQUIRED);

        assertThat(tickets.count()).isZero();
        assertThat(events.created).isEmpty();
    }

    @Test
    @DisplayName("하루 한도에 걸리면 429 이고 저장되지 않는다")
    void rateLimited() {
        given(rateLimiter.tryAcquire(eq("rl:support:create:" + me), anyInt(), any())).willReturn(false);

        assertThatThrownBy(() -> service.create(me, request("a", "b")))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
        assertThat(tickets.count()).isZero();
    }

    @Test
    @DisplayName("내 목록은 최신순 커서로 빠짐·중복 없이 끝나고, 남의 문의는 목록에도 상세에도 없다")
    void listIsMineOnly() {
        Long a = service.create(me, request("첫째", "b")).id();
        Long b = service.create(me, request("둘째", "b")).id();
        Long c = service.create(me, request("셋째", "b")).id();
        Long theirs = service.create(other, request("남의 것", "b")).id();

        SupportTicketPageResponse page1 = service.list(me, null, 2);
        assertThat(page1.items()).extracting("id").containsExactly(c, b);
        assertThat(page1.nextCursor()).isEqualTo(b);
        SupportTicketPageResponse page2 = service.list(me, page1.nextCursor(), 2);
        assertThat(page2.items()).extracting("id").containsExactly(a);
        assertThat(page2.nextCursor()).isNull();

        assertThatThrownBy(() -> service.get(me, theirs))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.SUPPORT_TICKET_NOT_FOUND);
        assertThatThrownBy(() -> service.addMessage(me, theirs, new CreateSupportMessageRequest("x")))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.SUPPORT_TICKET_NOT_FOUND);
    }

    @Test
    @DisplayName("답변 → ANSWERED·안 읽음·첫 답변 시각·푸시 이벤트, 사용자가 열면 읽음, 이어 쓰면 OPEN, 닫은 뒤 이어 써도 OPEN")
    void replyReadFollowUpClose() {
        Long id = service.create(me, request("제목", "본문")).id();

        SupportAdminTicketResponse replied = admin.reply(id, new CreateSupportMessageRequest("1.4.3 에서 고쳤어요"));
        assertThat(replied.status()).isEqualTo(SupportStatus.ANSWERED);
        assertThat(replied.userUnread()).isTrue();
        assertThat(replied.firstAnsweredAt()).isEqualTo(NOW_LDT);
        assertThat(replied.messages()).extracting("author").containsExactly(SupportAuthor.USER, SupportAuthor.ADMIN);
        assertThat(replied.user().id()).isEqualTo(me);
        assertThat(replied.user().nickname()).isEqualTo("나");
        assertThat(replied.user().withdrawn()).isFalse();
        assertThat(events.replied).singleElement().satisfies(e -> {
            assertThat(e.ticketId()).isEqualTo(id);
            assertThat(e.userId()).isEqualTo(me);
            assertThat(e.title()).isEqualTo("제목");
        });
        assertThat(service.list(me, null, null).items().get(0).userUnread()).isTrue();

        // 사용자가 상세를 열면 읽음
        SupportTicketResponse opened = service.get(me, id);
        assertThat(opened.userUnread()).isFalse();
        assertThat(tickets.findById(id).orElseThrow().isUserUnread()).isFalse();

        // 이어 쓰기 → OPEN, 웹훅용 이벤트
        SupportTicketResponse followed = service.addMessage(me, id, new CreateSupportMessageRequest("그래도 안 돼요"));
        assertThat(followed.status()).isEqualTo(SupportStatus.OPEN);
        assertThat(followed.messages()).hasSize(3);
        assertThat(events.messages).singleElement().extracting(SupportMessageCreatedEvent::ticketId).isEqualTo(id);

        // 두 번째 답변은 첫 답변 시각을 바꾸지 않는다
        admin.reply(id, new CreateSupportMessageRequest("로그를 보내 주세요"));
        assertThat(tickets.findById(id).orElseThrow().getFirstAnsweredAt()).isEqualTo(NOW_LDT);

        // 닫기 → CLOSED, 메모; 닫힌 문의에 이어 쓰면 OPEN
        SupportAdminTicketResponse closed = admin.update(id, new UpdateSupportTicketRequest(SupportStatus.CLOSED, "재현 안 됨"));
        assertThat(closed.status()).isEqualTo(SupportStatus.CLOSED);
        assertThat(closed.closedAt()).isEqualTo(NOW_LDT);
        assertThat(closed.adminMemo()).isEqualTo("재현 안 됨");
        assertThat(admin.update(id, new UpdateSupportTicketRequest(null, "")).adminMemo()).isNull();
        assertThat(service.addMessage(me, id, new CreateSupportMessageRequest("다시 생겼어요")).status()).isEqualTo(SupportStatus.OPEN);
        assertThat(tickets.findById(id).orElseThrow().getClosedAt()).isNull();

        assertThat(admin.count(SupportStatus.OPEN).count()).isEqualTo(1);
        assertThat(admin.list(SupportStatus.OPEN, null, null, null).items()).extracting("id").containsExactly(id);
        assertThat(admin.list(null, SupportCategory.PAYMENT, null, null).items()).isEmpty();
    }

    @Test
    @DisplayName("사진: 5장까지, 6장째 400, 5MB 초과 413, PNG/JPEG 아니면 400. 바이트가 그대로 돌아오고 지우면 바이트도 사라진다")
    void attachments() {
        Long id = service.create(me, request("제목", "본문")).id();
        byte[] png = png(1000);

        for (int i = 0; i < 5; i++) {
            SupportAttachmentResponse a = service.addAttachment(me, id, png, "application/octet-stream");
            assertThat(a.contentType()).isEqualTo("image/png");
            assertThat(a.sizeBytes()).isEqualTo(png.length);
            assertThat(a.url()).isEqualTo("https://api.peakpic.app/support/tickets/" + id + "/attachments/" + a.id());
        }
        assertThatThrownBy(() -> service.addAttachment(me, id, png, "image/png"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.SUPPORT_ATTACHMENT_LIMIT);

        Long id2 = service.create(me, request("둘째", "본문")).id();
        assertThatThrownBy(() -> service.addAttachment(me, id2, png(70_000), "image/png"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.SUPPORT_ATTACHMENT_TOO_LARGE);
        assertThatThrownBy(() -> service.addAttachment(me, id2, "not an image".getBytes(), "text/plain"))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.FILL_INVALID_IMAGE);
        assertThat(attachments.countByTicketId(id2)).isZero();

        SupportTicketResponse detail = service.get(me, id);
        assertThat(detail.attachments()).hasSize(5);
        Long attId = detail.attachments().get(0).id();
        StoredImage loaded = service.attachment(me, id, attId);
        assertThat(loaded.contentType()).isEqualTo("image/png");
        assertThat(loaded.data()).isEqualTo(png);
        assertThat(service.attachmentRedirect(me, id, attId)).isEmpty();   // RDS 저장소는 중계
        assertThat(admin.get(id).attachments().get(0).url())
                .isEqualTo("https://api.peakpic.app/admin/support/tickets/" + id + "/attachments/" + attId);
        assertThat(admin.attachment(id, attId).data()).isEqualTo(png);

        // 남의 사진은 못 본다
        assertThatThrownBy(() -> service.attachment(other, id, attId))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.SUPPORT_TICKET_NOT_FOUND);

        service.deleteAttachment(me, id, attId);
        assertThat(attachments.countByTicketId(id)).isEqualTo(4);
        assertThat(blobs.countByTicketId(id)).isEqualTo(4);
        assertThatThrownBy(() -> service.attachment(me, id, attId))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.SUPPORT_ATTACHMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("보관 정리: 마지막 메시지가 1년 넘은 문의는 메시지·사진·바이트까지 지워지고 최근 것은 남는다")
    void cleanup() {
        Long recent = service.create(me, request("최근", "본문")).id();
        service.addAttachment(me, recent, png(100), "image/png");
        Long old = service.create(me, request("오래됨", "본문")).id();
        service.addMessage(me, old, new CreateSupportMessageRequest("x"));
        service.addAttachment(me, old, png(100), "image/png");
        service.addAttachment(me, old, png(100), "image/png");
        assertThat(cleanup.runOnce(NOW_LDT)).isZero();   // 둘 다 방금 것

        jdbc.update("update support_tickets set last_message_at = ? where id = ?", NOW_LDT.minusDays(366), old);

        assertThat(cleanup.runOnce(NOW_LDT)).isEqualTo(1);

        assertThat(tickets.findById(old)).isEmpty();
        assertThat(attachments.countByTicketId(old)).isZero();
        assertThat(blobs.countByTicketId(old)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from support_messages where ticket_id = ?", Long.class, old)).isZero();
        assertThat(tickets.findById(recent)).isPresent();
        assertThat(blobs.countByTicketId(recent)).isEqualTo(1);
    }

    static CreateSupportTicketRequest request(String title, String body) {
        return new CreateSupportTicketRequest(SupportCategory.BUG, title, body, "1.4.2", "ANDROID", "Android 14", "SM-S928N");
    }

    static byte[] png(int size) {
        byte[] b = new byte[size];
        Arrays.fill(b, (byte) 7);
        System.arraycopy(PNG_MAGIC, 0, b, 0, PNG_MAGIC.length);
        return b;
    }
}
