package com.caestro.server.domain.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.caestro.server.domain.catalog.dto.request.UpsertRecommendRequest;
import com.caestro.server.domain.catalog.dto.response.CatalogRecommendFileResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogRecommendResponse;
import com.caestro.server.domain.catalog.entity.CatalogImage;
import com.caestro.server.domain.catalog.repository.CatalogBlobRepository;
import com.caestro.server.domain.catalog.repository.CatalogImageRepository;
import com.caestro.server.domain.catalog.repository.CatalogRecommendConfigRepository;
import com.caestro.server.domain.catalog.service.CatalogIdGenerator;
import com.caestro.server.domain.catalog.service.CatalogRecommendService;
import com.caestro.server.domain.catalog.store.RdsCatalogAssetStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실제 쿼리로 추천 설정(contracts/recommend-config.md)을 본다 (H2): 게시는 한 번에 하나, 게시 내리기, 고아 그림 정리가
 * 추천 설정이 쓰는 헤드·모델을 지우지 않는다, 복제한 버전이 같은 파일을 쓰면 하나를 지워도 파일이 남는다.
 */
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"}, showSql = false)
@ContextConfiguration(classes = CatalogRecommendPersistenceTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CatalogRecommendPersistenceTest {

    static final Instant NOW = Instant.parse("2026-11-10T08:00:00Z");

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = CatalogImage.class)
    @EnableJpaRepositories(basePackageClasses = CatalogImageRepository.class)
    @Import({CatalogRecommendService.class, RdsCatalogAssetStore.class, CatalogIdGenerator.class})
    static class Config {
        @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }

    @Autowired CatalogRecommendService service;
    @Autowired CatalogRecommendConfigRepository configs;
    @Autowired CatalogImageRepository images;
    @Autowired CatalogBlobRepository blobs;

    @BeforeEach
    void clean() {
        configs.deleteAll();
        images.deleteAll();
        blobs.deleteAll();
    }

    /** 장소 2개(거리·기타), feature_dim 1 의 가장 작은 헤드. */
    static byte[] tinyHead() {
        StringBuilder sb = new StringBuilder("{\"classes\":[\"거리\",\"기타\"],\"places365_bucket\":[");
        for (int i = 0; i < 365; i++) {
            sb.append(i == 0 ? "" : ",").append(i % 2);
        }
        sb.append("],\"feature_dim\":1,\"weights\":[[0.1,0.2,0.3],[0.4,0.5,0.6]],\"bias\":[0,0]}");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static UpsertRecommendRequest request(String name, String headId, String modelId) {
        return new UpsertRecommendRequest(name, null, Map.of("scoring", Map.of("listSize", 40)), headId, modelId);
    }

    @Test
    @DisplayName("게시는 한 번에 하나: 둘째를 게시하면 첫째가 내려가고, 게시 내리기 뒤에는 게시본이 없다")
    void publish_onlyOneActive() {
        CatalogRecommendResponse a = service.create(request("A", null, null));
        CatalogRecommendResponse b = service.create(request("B", null, null));

        service.publish(a.id());
        assertThat(service.manifest()).get().extracting(m -> m.id()).isEqualTo(a.id());

        service.publish(b.id());
        assertThat(service.manifest()).get().extracting(m -> m.id()).isEqualTo(b.id());
        assertThat(configs.findById(a.id()).orElseThrow().isActive()).isFalse();
        assertThat(configs.findById(a.id()).orElseThrow().getPublishedAt()).as("이력은 남는다").isNotNull();
        assertThat(service.list().activeId()).isEqualTo(b.id());
        assertThat(service.list().items()).filteredOn(CatalogRecommendResponse::active).hasSize(1);

        service.unpublish();
        assertThat(service.manifest()).isEmpty();
        assertThat(service.list().activeId()).isNull();
    }

    @Test
    @DisplayName("고아 그림 정리는 추천 설정이 쓰는 헤드·모델을 지우지 않고, 아무도 안 쓰는 파일만 고른다")
    void orphanQuery_keepsRecommendFiles() {
        CatalogRecommendFileResponse head = service.storeFile("head", tinyHead(), "application/json");
        CatalogRecommendFileResponse model = service.storeFile("model", new byte[]{8, 7, 6}, "application/octet-stream");
        CatalogRecommendFileResponse loose = service.storeFile("model", new byte[]{1}, "application/octet-stream");
        service.create(request("A", head.id(), model.id()));

        assertThat(images.findOrphanIds(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).plusDays(1)))
                .containsExactly(loose.id());
    }

    @Test
    @DisplayName("복제한 버전이 같은 헤드를 쓰면 하나를 지워도 헤드가 남고, 마지막 버전을 지우면 헤드 행과 바이트도 지운다")
    void delete_keepsSharedFile() {
        CatalogRecommendFileResponse head = service.storeFile("head", tinyHead(), "application/json");
        CatalogRecommendResponse a = service.create(request("A", head.id(), null));
        CatalogRecommendResponse copy = service.create(request("A 복제", head.id(), null));

        service.delete(a.id());
        assertThat(images.existsById(head.id())).isTrue();
        assertThat(blobs.existsById(head.id())).isTrue();

        service.delete(copy.id());
        assertThat(images.existsById(head.id())).isFalse();
        assertThat(blobs.existsById(head.id())).isFalse();
    }

    @Test
    @DisplayName("고치면서 헤드를 떼면(앱 번들로) 아무도 안 쓰게 된 헤드는 지운다. 게시본에는 head 가 null")
    void update_detachHead() {
        CatalogRecommendFileResponse head = service.storeFile("head", tinyHead(), "application/json");
        CatalogRecommendResponse a = service.create(request("A", head.id(), null));

        CatalogRecommendResponse updated = service.update(a.id(), request("A", null, null));
        assertThat(updated.head()).isNull();
        assertThat(images.existsById(head.id())).isFalse();

        service.publish(a.id());
        assertThat(service.manifest()).get().satisfies(m -> {
            assertThat(m.head()).isNull();
            assertThat(m.model()).isNull();
            assertThat(m.params()).isEqualTo(Map.of("scoring", Map.of("listSize", 40)));
        });
    }
}
