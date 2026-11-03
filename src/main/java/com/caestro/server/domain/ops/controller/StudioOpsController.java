package com.caestro.server.domain.ops.controller;

import com.caestro.server.domain.ops.controller.api.StudioOpsApi;
import com.caestro.server.domain.ops.dto.response.OpsStatsResponse;
import com.caestro.server.domain.ops.dto.response.OpsSubscriptionPageResponse;
import com.caestro.server.domain.ops.dto.response.OpsUserDetailResponse;
import com.caestro.server.domain.ops.dto.response.OpsUserPageResponse;
import com.caestro.server.domain.ops.service.StudioOpsService;
import com.caestro.server.domain.subscription.enums.SubscriptionStatus;
import com.caestro.server.domain.subscription.enums.SubscriptionStore;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 스튜디오 운영 화면용 읽기 전용. /admin/ops/** 은 SecurityConfig 가 ROLE_STUDIO·ADMIN 으로 막는다. */
@RestController
@RequestMapping("/admin/ops")
@RequiredArgsConstructor
public class StudioOpsController implements StudioOpsApi {

    private final StudioOpsService studioOpsService;

    @GetMapping("/stats")
    @Override
    public ResponseEntity<OpsStatsResponse> stats() {
        return ResponseEntity.ok(studioOpsService.stats());
    }

    @GetMapping("/users")
    @Override
    public ResponseEntity<OpsUserPageResponse> users(@RequestParam(required = false) String q,
                                                     @RequestParam(required = false) String provider,
                                                     @RequestParam(required = false) Boolean pro,
                                                     @RequestParam(required = false) Long cursor,
                                                     @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(studioOpsService.users(q, provider, pro, cursor, size));
    }

    @GetMapping("/users/{id}")
    @Override
    public ResponseEntity<OpsUserDetailResponse> user(@PathVariable Long id) {
        return ResponseEntity.ok(studioOpsService.user(id));
    }

    @GetMapping("/subscriptions")
    @Override
    public ResponseEntity<OpsSubscriptionPageResponse> subscriptions(@RequestParam(required = false) SubscriptionStatus status,
                                                                     @RequestParam(required = false) SubscriptionStore store,
                                                                     @RequestParam(required = false) Long cursor,
                                                                     @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(studioOpsService.subscriptions(status, store, cursor, size));
    }
}
