package com.caestro.server.domain.catalog.controller;

import com.caestro.server.domain.catalog.controller.api.CatalogStudioApi;
import com.caestro.server.domain.catalog.dto.request.UpsertBannerRequest;
import com.caestro.server.domain.catalog.dto.request.UpsertFrameRequest;
import com.caestro.server.domain.catalog.dto.request.UpsertPoseOverrideRequest;
import com.caestro.server.domain.catalog.dto.request.UpsertPoseUploadRequest;
import com.caestro.server.domain.catalog.dto.response.CatalogBannerResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogFrameResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogImageResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogPoseOverrideResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogPoseUploadResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogTrashResponse;
import com.caestro.server.domain.catalog.service.CatalogPoseService;
import com.caestro.server.domain.catalog.service.CatalogService;
import lombok.RequiredArgsConstructor;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 디자이너 스튜디오 쓰기. 경로 전체가 SecurityConfig 에서 ROLE_STUDIO(X-Studio-Key) 또는 ADMIN 만 통과한다.
 * 삭제는 휴지통으로 가고(7일), 복구와 완전 삭제가 따로 있다.
 */
@RestController
@RequestMapping("/catalog/studio")
@RequiredArgsConstructor
public class CatalogStudioController implements CatalogStudioApi {

    private final CatalogService catalogService;
    private final CatalogPoseService poseService;

    @PostMapping(value = "/images", consumes = {MediaType.IMAGE_PNG_VALUE, MediaType.IMAGE_JPEG_VALUE, MediaType.APPLICATION_OCTET_STREAM_VALUE})
    @Override
    public ResponseEntity<CatalogImageResponse> uploadImage(
            @RequestBody byte[] body,
            @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalogService.storeImage(body, contentType));
    }

    // ── 프레임 ──

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

    @PostMapping("/frames/{id}/restore")
    @Override
    public ResponseEntity<CatalogFrameResponse> restoreFrame(@PathVariable String id) {
        return ResponseEntity.ok(catalogService.restoreFrame(id));
    }

    // ── 배너 ──

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

    @PostMapping("/banners/{id}/restore")
    @Override
    public ResponseEntity<CatalogBannerResponse> restoreBanner(@PathVariable String id) {
        return ResponseEntity.ok(catalogService.restoreBanner(id));
    }

    // ── 포즈추천 ──

    @PutMapping("/pose/overrides/{version}/{shot}")
    @Override
    public ResponseEntity<CatalogPoseOverrideResponse> upsertPoseOverride(@PathVariable String version, @PathVariable String shot,
                                                                          @RequestBody UpsertPoseOverrideRequest request) {
        return ResponseEntity.ok(poseService.upsertOverride(version, shot, request));
    }

    @PostMapping("/pose/overrides/{version}/{shot}/restore")
    @Override
    public ResponseEntity<CatalogPoseOverrideResponse> restorePoseOverride(@PathVariable String version, @PathVariable String shot) {
        return ResponseEntity.ok(poseService.restoreOverride(version, shot));
    }

    @PostMapping("/pose/uploads")
    @Override
    public ResponseEntity<CatalogPoseUploadResponse> createPoseUpload(@RequestBody UpsertPoseUploadRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(poseService.createUpload(request));
    }

    @PutMapping("/pose/uploads/{id}")
    @Override
    public ResponseEntity<CatalogPoseUploadResponse> updatePoseUpload(@PathVariable String id, @RequestBody UpsertPoseUploadRequest request) {
        return ResponseEntity.ok(poseService.updateUpload(id, request));
    }

    @DeleteMapping("/pose/uploads/{id}")
    @Override
    public ResponseEntity<Void> deletePoseUpload(@PathVariable String id) {
        poseService.deleteUpload(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/pose/uploads/{id}/restore")
    @Override
    public ResponseEntity<CatalogPoseUploadResponse> restorePoseUpload(@PathVariable String id) {
        return ResponseEntity.ok(poseService.restoreUpload(id));
    }

    // ── 휴지통 ──

    @GetMapping("/trash")
    @Override
    public ResponseEntity<CatalogTrashResponse> trash() {
        return ResponseEntity.ok(new CatalogTrashResponse(catalogService.trashedFrames(), catalogService.trashedBanners(),
                poseService.trashedUploads(), poseService.trashedOverrides()));
    }

    @DeleteMapping("/trash/frames/{id}")
    @Override
    public ResponseEntity<Void> purgeFrame(@PathVariable String id) {
        catalogService.purgeFrame(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/trash/banners/{id}")
    @Override
    public ResponseEntity<Void> purgeBanner(@PathVariable String id) {
        catalogService.purgeBanner(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/trash/pose/uploads/{id}")
    @Override
    public ResponseEntity<Void> purgePoseUpload(@PathVariable String id) {
        poseService.purgeUpload(id);
        return ResponseEntity.noContent().build();
    }
}
