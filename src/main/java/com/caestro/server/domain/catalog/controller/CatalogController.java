package com.caestro.server.domain.catalog.controller;
import java.util.Optional;
import java.net.URI;

import com.caestro.server.domain.catalog.controller.api.CatalogApi;
import com.caestro.server.domain.catalog.dto.response.CatalogBannerListResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogFrameListResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogFrameResponse;
import com.caestro.server.domain.catalog.dto.response.CatalogPoseResponse;
import com.caestro.server.domain.catalog.service.CatalogPoseService;
import com.caestro.server.domain.catalog.service.CatalogService;
import com.caestro.server.domain.catalog.store.CatalogAsset;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 카탈로그 읽기(인증 없음, SecurityConfig permitAll). 쓰기는 {@link CatalogStudioController}. */
@RestController
@RequestMapping("/catalog")
@RequiredArgsConstructor
public class CatalogController implements CatalogApi {

    private final CatalogService catalogService;
    private final CatalogPoseService poseService;
    private final com.caestro.server.domain.catalog.service.CatalogBuiltinService builtinService;

    @GetMapping("/frames")
    @Override
    public ResponseEntity<CatalogFrameListResponse> frames(
            @RequestParam(required = false, defaultValue = "false") boolean all,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            HttpServletRequest request) {
        boolean includeHidden = all && isStudio(request);
        // 스튜디오가 덮어쓴 기본 프레임(5차)도 함께: 앱이 이름·Pro·숨김을 기본 프레임에 덮어씌운다
        CatalogFrameListResponse body = catalogService.frames(includeHidden).withBuiltins(builtinService.all());
        String etag = etag("f", includeHidden, body.updatedAt(), body.frames().size() * 100 + body.builtins().size());
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok().eTag(etag).cacheControl(CacheControl.noCache()).body(body);
    }

    @GetMapping("/frames/{id}")
    @Override
    public ResponseEntity<CatalogFrameResponse> frame(@PathVariable String id) {
        return ResponseEntity.ok(catalogService.frame(id));
    }

    @GetMapping("/banners")
    @Override
    public ResponseEntity<CatalogBannerListResponse> banners(
            @RequestParam(required = false, defaultValue = "false") boolean all,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            HttpServletRequest request) {
        boolean includeHidden = all && isStudio(request);
        CatalogBannerListResponse body = catalogService.banners(includeHidden);
        String etag = etag("b", includeHidden, body.updatedAt(), body.banners().size());
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok().eTag(etag).cacheControl(CacheControl.noCache()).body(body);
    }

    @GetMapping("/pose")
    @Override
    public ResponseEntity<CatalogPoseResponse> pose(
            @RequestParam(required = false, defaultValue = "false") boolean all,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            HttpServletRequest request) {
        boolean includeHidden = all && isStudio(request);
        CatalogPoseResponse body = poseService.pose(includeHidden);
        String etag = etag("p", includeHidden, body.updatedAt(), body.overrides().size() + body.uploads().size());
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok().eTag(etag).cacheControl(CacheControl.noCache()).body(body);
    }

    // 내용이 바뀌지 않는 난수 id 라 오래 캐시한다. 저장소가 CDN 주소를 주면(S3, #186) 302 로 보내고 그 302 도 오래 캐시한다 —
    // 주소가 바뀌지 않으니 브라우저는 다음부터 우리 서버를 거치지 않는다. 중계할 때 동영상은 Resource 로 내줘 Spring 이 Range 요청에 206 으로 답한다
    @GetMapping("/images/{imageId}")
    @Override
    public ResponseEntity<?> image(@PathVariable String imageId) {
        Optional<URI> redirect = catalogService.imageRedirect(imageId);
        if (redirect.isPresent()) {
            return ResponseEntity.status(HttpStatus.FOUND).location(redirect.get())
                    .cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic()).build();
        }
        CatalogAsset image = catalogService.image(imageId);
        ResponseEntity.BodyBuilder ok = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic());
        if (image.contentType().startsWith("video/")) {
            return ok.body(new ByteArrayResource(image.data()));
        }
        return ok.body(image.data());
    }

    /** 약한 ETag: 가장 최근 수정 시각과 개수. 숨김 포함 여부가 다르면 다른 값이어야 하므로 섞는다. */
    static String etag(String kind, boolean includeHidden, Instant updatedAt, int count) {
        long stamp = updatedAt == null ? 0 : updatedAt.toEpochMilli();
        return "W/\"" + kind + (includeHidden ? "a" : "p") + "-" + stamp + "-" + count + "\"";
    }

    private static boolean isStudio(HttpServletRequest request) {
        return request.isUserInRole("STUDIO") || request.isUserInRole("ADMIN");
    }
}
