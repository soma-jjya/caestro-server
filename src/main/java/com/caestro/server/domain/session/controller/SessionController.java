package com.caestro.server.domain.session.controller;

import com.caestro.server.domain.session.controller.api.SessionApi;
import com.caestro.server.domain.session.dto.response.SessionResponse;
import com.caestro.server.domain.session.entity.Session;
import com.caestro.server.domain.session.service.SessionService;
import com.caestro.server.global.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sessions")
@RequiredArgsConstructor
public class SessionController implements SessionApi {

    private final SessionService sessionService;

    @GetMapping("/{sessionId}")
    @Override
    public ResponseEntity<SessionResponse> getSession(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Session session = sessionService.getOwnedSession(
                sessionId, userDetails.getUserId(), userDetails.getRole());
        return ResponseEntity.ok(SessionResponse.from(session));
    }
}
