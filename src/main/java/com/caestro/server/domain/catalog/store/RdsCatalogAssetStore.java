package com.caestro.server.domain.catalog.store;

import com.caestro.server.domain.catalog.entity.CatalogBlob;
import com.caestro.server.domain.catalog.repository.CatalogBlobRepository;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * RDS 구현: 바이트를 {@code catalog_blobs} 에 둔다. 기본값이라 로컬·CI 는 설정 없이 이것을 쓴다.
 * 운영은 {@code catalog.asset-store=s3} 로 바꾼다(#186 2단계). 주소는 서버가 중계하는 {@code /catalog/images/{id}} 다.
 */
@Component
@ConditionalOnProperty(name = "catalog.asset-store", havingValue = "rds", matchIfMissing = true)
public class RdsCatalogAssetStore implements CatalogAssetStore {

    // #186 이전에 바이트가 있던 자리. 1단계 배포 직후 아직 옮기지 않은 행을 읽기 위한 폴백이며, 컬럼을 지우면 자연히 빈 값이 된다
    private static final List<String> LEGACY_TABLES = List.of("catalog_images", "b2b_images");

    private final CatalogBlobRepository blobs;
    private final JdbcTemplate jdbc;
    private final String publicBaseUrl;

    public RdsCatalogAssetStore(CatalogBlobRepository blobs, JdbcTemplate jdbc,
                                // ALB 뒤에서는 요청 스킴을 믿을 수 없어 호스트를 고정한다(fill 과 같은 이유)
                                @Value("${catalog.public-base-url:https://api.peakpic.app}") String publicBaseUrl) {
        this.blobs = blobs;
        this.jdbc = jdbc;
        this.publicBaseUrl = publicBaseUrl;
    }

    @Override
    @Transactional
    public void put(String id, String contentType, byte[] data) {
        blobs.save(CatalogBlob.builder().id(id).contentType(contentType).data(data).build());
    }

    @Override
    @Transactional
    public void copy(String sourceId, String targetId) {
        CatalogAsset source = load(sourceId);
        blobs.save(CatalogBlob.builder().id(targetId).contentType(source.contentType()).data(source.data()).build());
    }

    @Override
    @Transactional(readOnly = true)
    public CatalogAsset load(String id) {
        return blobs.findById(id)
                .map(blob -> new CatalogAsset(blob.getId(), blob.getContentType(), blob.getData()))
                .or(() -> legacy(id))
                .orElseThrow(() -> new CustomException(ErrorCode.CATALOG_IMAGE_NOT_FOUND));
    }

    /** 옛 자리(메타 행의 data 컬럼)에 남은 바이트. 컬럼이 없는 DB(새 설치·CI·컬럼 삭제 뒤)에서는 조회가 실패하므로 빈 값. */
    private Optional<CatalogAsset> legacy(String id) {
        for (String table : LEGACY_TABLES) {
            try {
                List<CatalogAsset> rows = jdbc.query(
                        "select content_type, data from " + table + " where id = ? and data is not null",
                        (rs, i) -> new CatalogAsset(id, rs.getString(1), rs.getBytes(2)), id);
                if (!rows.isEmpty()) {
                    return Optional.of(rows.get(0));
                }
            } catch (DataAccessException e) {
                // data 컬럼이 없는 테이블: 옮길 것도 없다
            }
        }
        return Optional.empty();
    }

    @Override
    @Transactional
    public void delete(String id) {
        blobs.findById(id).ifPresent(blobs::delete);
    }

    @Override
    public String urlOf(String id) {
        return publicBaseUrl + "/catalog/images/" + id;
    }
}
