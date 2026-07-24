package com.caestro.server.domain.turn.controller;

import com.caestro.server.domain.turn.controller.api.TurnApi;
import com.caestro.server.domain.turn.dto.response.TurnCredentialResponse;
import com.caestro.server.domain.turn.service.TurnCredentialService;
import com.caestro.server.global.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/turn-credentials")
@RequiredArgsConstructor
public class TurnController implements TurnApi {

    private final TurnCredentialService turnCredentialService;

    @GetMapping
    @Override
    public ResponseEntity<TurnCredentialResponse> getTurnCredentials(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(turnCredentialService.generate(userDetails.getUserId()));
    }
}
