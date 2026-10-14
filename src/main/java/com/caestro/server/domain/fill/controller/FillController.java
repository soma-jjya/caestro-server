package com.caestro.server.domain.fill.controller;

import com.caestro.server.domain.fill.controller.api.FillApi;
import com.caestro.server.domain.fill.dto.request.CreateFillRequest;
import com.caestro.server.domain.fill.dto.request.UpdateFrameRequest;
import com.caestro.server.domain.fill.dto.response.AssetUrlResponse;
import com.caestro.server.domain.fill.dto.response.FillResponse;
import com.caestro.server.domain.fill.entity.FillImage;
import com.caestro.server.domain.fill.service.FillService;
import com.caestro.server.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/fills")
@RequiredArgsConstructor
public class FillController implements FillApi {

    private final FillService fillService;

    @PostMapping
    @Override
    public ResponseEntity<FillResponse> create(
            @Valid @RequestBody CreateFillRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED).body(fillService.create(userDetails.getUserId(), request));
    }

    @GetMapping("/{code}")
    @Override
    public ResponseEntity<FillResponse> get(
            @PathVariable String code,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(fillService.get(code, userDetails.getUserId()));
    }

    @PutMapping("/{code}/frame")
    @Override
    public ResponseEntity<FillResponse> updateFrame(
            @PathVariable String code,
            @Valid @RequestBody UpdateFrameRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(fillService.updateFrame(code, userDetails.getUserId(), request));
    }

    @PutMapping(value = "/{code}/slots/{index}", consumes = {MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE, MediaType.APPLICATION_OCTET_STREAM_VALUE})
    @Override
    public ResponseEntity<FillResponse> fillSlot(
            @PathVariable String code,
            @PathVariable int index,
            @RequestParam(required = false) String name,
            @RequestBody byte[] body,
            @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(fillService.fillSlot(code, index, userDetails.getUserId(), name, body, contentType));
    }

    @DeleteMapping("/{code}/slots/{index}")
    @Override
    public ResponseEntity<FillResponse> clearSlot(
            @PathVariable String code,
            @PathVariable int index,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(fillService.clearSlot(code, index, userDetails.getUserId()));
    }

    @PutMapping(value = "/{code}/frame-assets/{k}", consumes = {MediaType.IMAGE_PNG_VALUE, MediaType.APPLICATION_OCTET_STREAM_VALUE})
    @Override
    public ResponseEntity<AssetUrlResponse> uploadFrameAsset(
            @PathVariable String code,
            @PathVariable int k,
            @RequestBody byte[] body,
            @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(new AssetUrlResponse(fillService.uploadFrameAsset(code, k, userDetails.getUserId(), body, contentType)));
    }

    @PutMapping(value = "/{code}/gif", consumes = {MediaType.IMAGE_GIF_VALUE, MediaType.APPLICATION_OCTET_STREAM_VALUE})
    @Override
    public ResponseEntity<AssetUrlResponse> uploadGif(
            @PathVariable String code,
            @RequestBody byte[] body,
            @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(new AssetUrlResponse(fillService.uploadGif(code, userDetails.getUserId(), body, contentType)));
    }

    @DeleteMapping("/{code}")
    @Override
    public ResponseEntity<Void> delete(
            @PathVariable String code,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        fillService.delete(code, userDetails.getUserId());
        return ResponseEntity.noContent().build();
    }

    // 인증 없음(SecurityConfig permitAll). 내용은 바뀌지 않는 id라 오래 캐시해도 된다.
    @GetMapping("/{code}/images/{imageId}")
    @Override
    public ResponseEntity<byte[]> image(@PathVariable String code, @PathVariable String imageId) {
        FillImage image = fillService.image(code, imageId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.getContentType()))
                .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic())
                .body(image.getData());
    }
}
