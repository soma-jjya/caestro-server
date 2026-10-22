package com.caestro.server.domain.catalog.controller;

import com.caestro.server.domain.catalog.controller.api.CatalogStudioApi;
import com.caestro.server.domain.catalog.dto.request.UpsertBannerRequest;
import com.caestro.server.domain.catalog.dto.request.UpsertFrameRequest;
import com.caestro.server.domain.catalog.dto.response.CatalogBannerResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogFrameResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogImageResponse;
import com.caestro.server.domain.catalog.service.CatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 디자이너 스튜디오 쓰기. 경로 전체가 SecurityConfig 에서 ROLE_STUDIO(X-Studio-Key) 또는 ADMIN 만 통과한다. */
@RestController
@RequestMapping("/catalog/studio")
@RequiredArgsConstructor
public class CatalogStudioController implements CatalogStudioApi {

    private final CatalogService catalogService;

    @PostMapping(value = "/images", consumes = {MediaType.IMAGE_PNG_VALUE, MediaType.IMAGE_JPEG_VALUE, MediaType.APPLICATION_OCTET_STREAM_VALUE})
    @Override
    public ResponseEntity<CatalogImageResponse> uploadImage(
            @RequestBody byte[] body,
            @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalogService.storeImage(body, contentType));
    }

    @PostMapping("/frames")
    @Override
    public ResponseEntity<CatalogFrameResponse> createFrame(@RequestBody UpsertFrameRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalogService.createFrame(request));
    }

    @PutMapping("/frames/{id}")
    @Override
    public ResponseEntity<CatalogFrameResponse> updateFrame(@PathVariable String id, @RequestBody UpsertFrameRequest request) {
        return ResponseEntity.ok(catalogService.updateFrame(id, request));
    }

    @DeleteMapping("/frames/{id}")
    @Override
    public ResponseEntity<Void> deleteFrame(@PathVariable String id) {
        catalogService.deleteFrame(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/banners")
    @Override
    public ResponseEntity<CatalogBannerResponse> createBanner(@RequestBody UpsertBannerRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalogService.createBanner(request));
    }

    @PutMapping("/banners/{id}")
    @Override
    public ResponseEntity<CatalogBannerResponse> updateBanner(@PathVariable String id, @RequestBody UpsertBannerRequest request) {
        return ResponseEntity.ok(catalogService.updateBanner(id, request));
    }

    @DeleteMapping("/banners/{id}")
    @Override
    public ResponseEntity<Void> deleteBanner(@PathVariable String id) {
        catalogService.deleteBanner(id);
        return ResponseEntity.noContent().build();
    }
}
