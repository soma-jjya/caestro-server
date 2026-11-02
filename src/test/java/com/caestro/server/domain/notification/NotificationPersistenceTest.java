package com.caestro.server.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.caestro.server.domain.notification.dto.response.NotificationPageResponse;
import com.caestro.server.domain.notification.entity.Notification;
import com.caestro.server.domain.notification.repository.NotificationRepository;
import com.caestro.server.domain.notification.service.NotificationService;
import com.caestro.server.domain.push.sender.PushMessage;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 실제 쿼리로 커서 페이지·안 읽은 개수·모두 읽음·보관 정리를 검증한다 (H2). */
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.show-sql=false"}, showSql = false)
@ContextConfiguration(classes = NotificationPersistenceTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationPersistenceTest {

    @Configuration(proxyBeanMethods = false)
    @EnableJpaAuditing
    @EntityScan(basePackageClasses = {Notification.class, User.class})
    @EnableJpaRepositories(basePackageClasses = {NotificationRepository.class, UserRepository.class})
    @Import(NotificationService.class)
    static class Config {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-11-02T12:00:00Z"), ZoneOffset.UTC); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean MeterRegistry meterRegistry() { return new SimpleMeterRegistry(); }
    }

    @Autowired NotificationService service;
    @Autowired NotificationRepository repository;
    @Autowired UserRepository users;

    private Long me;
    private Long other;

    @BeforeEach
    void setUp() {
        // 트랜잭션 롤백 없이 도는 테스트라 사용자는 매번 새 deviceId 로 만든다(users.device_id 유일 제약)
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        repository.deleteAll();
        me = users.save(User.builder().deviceId("me-" + suffix).role(User.Role.USER).build()).getId();
        other = users.save(User.builder().deviceId("other-" + suffix).role(User.Role.USER).build()).getId();
    }

    @Test
    @DisplayName("커서 페이지: 5건을 2건씩 넘기면 최신순으로 빠짐·중복 없이 끝나고, 남의 알림은 섞이지 않는다")
    void cursorPagesWithoutGapsOrDuplicates() {
        for (int i = 0; i < 5; i++) {
            service.record(me, PushMessage.fillCompleted("CODE0" + i));
        }
        service.record(other, PushMessage.fillCompleted("OTHER1"));

        List<Long> seen = new ArrayList<>();
        Long cursor = null;
        int pages = 0;
        do {
            NotificationPageResponse page = service.list(me, cursor, 2);
            page.items().forEach(item -> seen.add(item.id()));
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(seen).hasSize(5).doesNotHaveDuplicates().isSortedAccordingTo((a, b) -> Long.compare(b, a));
        assertThat(service.list(me, null, 50).items()).allSatisfy(item ->
                assertThat(item.data().get("code")).startsWith("CODE"));
    }

    @Test
    @DisplayName("안 읽은 개수는 본인 것만 세고, 하나 읽음·모두 읽음 뒤 줄어든다")
    void unreadCountFollowsReads() {
        Notification first = service.record(me, PushMessage.fillCompleted("CODE01"));
        service.record(me, PushMessage.fillCompleted("CODE02"));
        service.record(other, PushMessage.fillCompleted("OTHER1"));

        assertThat(service.unreadCount(me).unread()).isEqualTo(2);
        service.markRead(me, first.getId());
        assertThat(service.unreadCount(me).unread()).isEqualTo(1);
        assertThat(service.markAllRead(me)).isEqualTo(1);
        assertThat(service.unreadCount(me).unread()).isZero();
        assertThat(service.unreadCount(other).unread()).isEqualTo(1);
    }

    @Test
    @DisplayName("보관 정리: 기준 시각 이전 행만 지운다")
    void cleanupDeletesOnlyOlderRows() {
        service.record(me, PushMessage.fillCompleted("CODE01"));
        assertThat(service.deleteCreatedBefore(LocalDateTime.now().minusDays(1))).isZero();
        assertThat(service.deleteCreatedBefore(LocalDateTime.now().plusDays(1))).isEqualTo(1);
        assertThat(repository.count()).isZero();
    }
}
