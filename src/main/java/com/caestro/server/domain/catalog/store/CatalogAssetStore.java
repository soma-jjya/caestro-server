package com.caestro.server.domain.catalog.store;

import java.net.URI;
import java.util.Optional;

/**
 * 카탈로그 자산(프레임 PNG·배너 그림·배너 MP4·포즈 사진·B2B 사본)의 바이트 저장소 (#186).
 * 메타데이터(폭·높이·종류·올린 시각)는 {@code catalog_images}·{@code b2b_images} 행이 들고, 바이트만 여기 둔다.
 * 함께 채우기의 {@code FillImageStore}(#152)와 같은 분리다 — 구현을 바꿔도(RDS → S3+CDN) 서비스 코드는 그대로다.
 *
 * <p>키는 자산 id 하나다. 같은 id 로 두 번 넣지 않는다(난수 id).
 */
public interface CatalogAssetStore {

    /** 바이트를 저장한다. 메타 행은 호출자가 만든다. */
    void put(String id, String contentType, byte[] data);

    /**
     * 다른 id 로 사본을 만든다(B2B 가 카탈로그 업로드를 자기 수명으로 보관할 때). 원본이 없으면 CATALOG_IMAGE_NOT_FOUND.
     */
    void copy(String sourceId, String targetId);

    /** 바이트를 읽어 중계할 때. 없으면 CATALOG_IMAGE_NOT_FOUND. S3 구현은 {@link #redirectUrl} 이 우선이다. */
    CatalogAsset load(String id);

    /** 클라이언트를 직접 보낼 주소(S3 + CDN). 비어 있으면 서버가 {@link #load} 로 중계한다. RDS 구현은 늘 비어 있다. */
    default Optional<URI> redirectUrl(String id) {
        return Optional.empty();
    }

    /** 바이트를 지운다. 메타 행은 호출자가 지운다. 없어도 조용히 넘어간다. */
    void delete(String id);

    /** 응답에 싣는 공개 주소. 저장소가 바뀌면 호스트가 바뀐다(api.peakpic.app → CDN). */
    String urlOf(String id);
}
