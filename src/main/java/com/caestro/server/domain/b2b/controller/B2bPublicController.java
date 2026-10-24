package com.caestro.server.domain.b2b.controller;

import com.caestro.server.domain.b2b.controller.api.B2bPublicApi;
import com.caestro.server.domain.b2b.dto.request.CreateB2bFillRequest;
import com.caestro.server.domain.b2b.dto.response.B2bFillResponse;
import com.caestro.server.domain.b2b.dto.response.B2bPublicProjectResponse;
import com.caestro.server.domain.b2b.service.B2bFillService;
import com.caestro.server.domain.b2b.service.B2bImageService;
import com.caestro.server.domain.b2b.service.B2bProjectService;
import com.caestro.server.domain.fill.service.FillWriteGuard;
import com.caestro.server.domain.fill.store.StoredImage;
import com.caestro.server.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/b2b")
@RequiredArgsConstructor
public class B2bPublicController implements B2bPublicApi {
    private final B2bProjectService projects;
    private final B2bFillService fills;
    private final B2bImageService images;
    private final FillWriteGuard writeGuard;

    @Override @GetMapping("/projects/{slug}")
    public ResponseEntity<B2bPublicProjectResponse> project(@PathVariable String slug) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(projects.publicProject(slug));
    }

    @Override @PostMapping("/projects/{slug}/fills")
    public ResponseEntity<B2bFillResponse> create(@PathVariable String slug, @Valid @RequestBody CreateB2bFillRequest request,
                                                @AuthenticationPrincipal CustomUserDetails user) {
        writeGuard.checkCreate(user.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(fills.create(slug, user.getUserId(), request));
    }

    @Override @GetMapping("/projects/{slug}/fills/{code}")
    public ResponseEntity<B2bFillResponse> get(@PathVariable String slug, @PathVariable String code,
                                             @AuthenticationPrincipal CustomUserDetails user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(fills.get(slug, code, user.getUserId()));
    }

    @Override @GetMapping("/images/{id}")
    public ResponseEntity<byte[]> image(@PathVariable String id) {
        StoredImage image = images.image(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.contentType()))
                .cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic()).body(image.data());
    }
}
