package com.caestro.server.domain.dailylimit.controller;

import com.caestro.server.domain.dailylimit.controller.api.DailyLimitApi;
import com.caestro.server.domain.dailylimit.dto.request.DailyUsageConsumeRequest;
import com.caestro.server.domain.dailylimit.dto.response.DailyUsageResponse;
import com.caestro.server.domain.dailylimit.enums.DailyAction;
import com.caestro.server.domain.dailylimit.service.DailyUsageService;
import com.caestro.server.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/limits/fourcut")
@RequiredArgsConstructor
public class DailyLimitController implements DailyLimitApi {

    private final DailyUsageService dailyUsageService;

    @GetMapping
    @Override
    public ResponseEntity<DailyUsageResponse> fourcutStatus(@AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(dailyUsageService.status(userDetails.getUserId(), DailyAction.FOURCUT_SAVE));
    }

    @PostMapping
    @Override
    public ResponseEntity<DailyUsageResponse> consumeFourcut(@Valid @RequestBody DailyUsageConsumeRequest request,
                                                             @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(dailyUsageService.consume(
                userDetails.getUserId(), DailyAction.FOURCUT_SAVE, request.projectId()));
    }
}
