package com.caestro.server.domain.catalog.dto.response;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * {@code updatedAt} 은 목록 중 가장 최근 수정 시각(비어 있으면 null) — 클라이언트 캐시와 ETag 의 근거.
 * {@code builtins} 는 스튜디오가 덮어쓴 기본 프레임(카탈로그 5차, 덮어쓴 것만. 숨김·휴지통 포함 — 앱이 목록에서 빼야 하므로).
 */
public record CatalogFrameListResponse(Instant updatedAt, List<CatalogFrameResponse> frames, List<CatalogBuiltinFrameResponse> builtins) {

    public CatalogFrameListResponse(Instant updatedAt, List<CatalogFrameResponse> frames) {
        this(updatedAt, frames, List.of());
    }

    /** 기본 프레임 덮어쓰기를 붙이고, 수정 시각은 둘 중 늦은 것으로 */
    public CatalogFrameListResponse withBuiltins(List<CatalogBuiltinFrameResponse> list) {
        Instant latest = Stream.concat(Stream.of(updatedAt), list.stream().map(CatalogBuiltinFrameResponse::updatedAt))
                .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        return new CatalogFrameListResponse(latest, frames, list);
    }
}
