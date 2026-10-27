package com.caestro.server.domain.catalog.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caestro.server.domain.catalog.entity.CatalogBlob;
import com.caestro.server.domain.catalog.repository.CatalogBlobRepository;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

/**
 * RDS 자산 저장소(#186 1단계): 넣기·읽기·사본·지우기가 catalog_blobs 로 오가고, 메타 행 없이 바이트만 다룬다.
 * H2 로 돌아 외부 DB 가 필요 없다.
 */
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"}, showSql = false)
@ContextConfiguration(classes = RdsCatalogAssetStoreTest.Config.class)
class RdsCatalogAssetStoreTest {

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = CatalogBlob.class)
    @EnableJpaRepositories(basePackageClasses = CatalogBlobRepository.class)
    @Import(RdsCatalogAssetStore.class)
    static class Config {
    }

    @Autowired private CatalogAssetStore store;
    @Autowired private CatalogBlobRepository blobs;

    @Test
    @DisplayName("넣은 바이트를 같은 id 로 읽고, 사본은 다른 id 로 같은 내용, 지우면 404")
    void putLoadCopyDelete() {
        store.put("img1", "image/png", new byte[]{1, 2, 3});

        CatalogAsset loaded = store.load("img1");
        assertThat(loaded.contentType()).isEqualTo("image/png");
        assertThat(loaded.data()).containsExactly(1, 2, 3);

        store.copy("img1", "bi_copy");
        assertThat(store.load("bi_copy").data()).containsExactly(1, 2, 3);
        assertThat(blobs.count()).isEqualTo(2);

        store.delete("img1");
        store.delete("img1");   // 없어도 조용히
        assertThatThrownBy(() -> store.load("img1"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CATALOG_IMAGE_NOT_FOUND);
        assertThat(store.load("bi_copy").data()).as("사본은 원본과 수명이 다르다").containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("모르는 id 는 404, 주소는 서버 중계 경로")
    void unknownAndUrl() {
        assertThatThrownBy(() -> store.load("nope")).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> store.copy("nope", "x")).isInstanceOf(CustomException.class);
        assertThat(store.urlOf("abc")).isEqualTo("https://api.peakpic.app/catalog/images/abc");
    }
}
