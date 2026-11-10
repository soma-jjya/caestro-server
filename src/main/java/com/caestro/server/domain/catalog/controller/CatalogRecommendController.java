package com.caestro.server.domain.catalog.controller;

import com.caestro.server.domain.catalog.controller.api.CatalogRecommendApi;
import com.caestro.server.domain.catalog.dto.request.UpsertRecommendRequest;
import com.caestro.server.domain.catalog.dto.response.CatalogRecommendFileResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogRecommendListResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogRecommendManifest;
import com.caestro.server.domain.catalog.dto.response.CatalogRecommendResponse;
import com.caestro.server.domain.catalog.service.CatalogRecommendService;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 포즈추천 알고리즘 설정 (#260, contracts/recommend-config.md). 앱이 받는 게시본 {@code GET /catalog/recommend} 는 인증 없음,
 * {@code /catalog/studio/recommend/**} 는 SecurityConfig 가 ROLE_STUDIO(X-Studio-Key)·ADMIN 으로 막는다.
 */
@RestController
@RequiredArgsConstructor
public class CatalogRecommendController implements CatalogRecommendApi {

    private final CatalogRecommendService recommendService;

    // 다른 카탈로그 목록과 같이 ETag + no-cache: 앱은 시작 때마다 If-None-Match 로 묻고 바뀌지 않았으면 304 를 받는다
    @GetMapping("/catalog/recommend")
    @Override
    public ResponseEntity<Object> manifest(@RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        Optional<CatalogRecommendManifest> manifest = recommendService.manifest();
        String etag = CatalogRecommendService.etag(manifest);
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        // 게시본이 없으면 빈 객체 {} — 앱은 번들 기본값을 쓴다
        Object body = manifest.<Object>map(m -> m).orElseGet(Map::of);
        return ResponseEntity.ok().eTag(etag).cacheControl(CacheControl.noCache()).body(body);
    }

    @GetMapping("/catalog/studio/recommend")
    @Override
    public ResponseEntity<CatalogRecommendListResponse> list() {
        return ResponseEntity.ok(recommendService.list());
    }

    @PostMapping("/catalog/studio/recommend")
    @Override
    public ResponseEntity<CatalogRecommendResponse> create(@RequestBody UpsertRecommendRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(recommendService.create(request));
    }

    @PutMapping("/catalog/studio/recommend/{id}")
    @Override
    public ResponseEntity<CatalogRecommendResponse> update(@PathVariable String id, @RequestBody UpsertRecommendRequest request) {
        return ResponseEntity.ok(recommendService.update(id, request));
    }

    @PostMapping("/catalog/studio/recommend/{id}/publish")
    @Override
    public ResponseEntity<CatalogRecommendResponse> publish(@PathVariable String id) {
        return ResponseEntity.ok(recommendService.publish(id));
    }

    @PostMapping("/catalog/studio/recommend/unpublish")
    @Override
    public ResponseEntity<Void> unpublish() {
        recommendService.unpublish();
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/catalog/studio/recommend/{id}")
    @Override
    public ResponseEntity<Void> delete(@PathVariable String id) {
        recommendService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // 헤드는 application/json, 모델은 application/octet-stream. 본문 크기는 RecommendFileSizeInterceptor 가 먼저 본다
    @PostMapping(value = "/catalog/studio/recommend/files", consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_OCTET_STREAM_VALUE})
    @Override
    public ResponseEntity<CatalogRecommendFileResponse> uploadFile(
            @RequestParam(required = false) String kind,
            // 빈 본문도 받아 서비스가 무엇이 틀렸는지 400 으로 답하게 한다
            @RequestBody(required = false) byte[] body,
            @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType) {
        return ResponseEntity.status(HttpStatus.CREATED).body(recommendService.storeFile(kind, body, contentType));
    }
}
