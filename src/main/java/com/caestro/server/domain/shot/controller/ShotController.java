package com.caestro.server.domain.shot.controller;

import com.caestro.server.domain.shot.controller.api.ShotApi;
import com.caestro.server.domain.shot.dto.request.CreateShotRequest;
import com.caestro.server.domain.shot.dto.response.ShotResponse;
import com.caestro.server.domain.shot.entity.Shot;
import com.caestro.server.domain.shot.service.ShotService;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import com.caestro.server.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/shots")
@RequiredArgsConstructor
public class ShotController implements ShotApi {

    private final ShotService shotService;

    @PostMapping
    @Override
    public ResponseEntity<ShotResponse> createShot(
            @Valid @RequestBody CreateShotRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Shot shot = shotService.createShot(userDetails.getUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ShotResponse.from(shot));
    }

    @GetMapping
    @Override
    public ResponseEntity<Page<ShotResponse>> getMyShots(
            @PageableDefault(size = 20, sort = "takenAt", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Page<ShotResponse> shots = shotService.getMyShots(userDetails.getUserId(), pageable)
                .map(ShotResponse::from);
        return ResponseEntity.ok(shots);
    }

    @GetMapping("/{id}")
    @Override
    public ResponseEntity<ShotResponse> getShot(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Shot shot = shotService.getOwnedShot(id, userDetails.getUserId());
        return ResponseEntity.ok(ShotResponse.from(shot));
    }
}
