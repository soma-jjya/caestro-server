package com.caestro.server.domain.push.service;

import com.caestro.server.domain.push.dto.request.RegisterPushTokenRequest;
import com.caestro.server.domain.push.dto.response.PushTokenResponse;
import com.caestro.server.domain.push.entity.PushToken;
import com.caestro.server.domain.push.repository.PushTokenRepository;
import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기기 토큰 등록·해제 (#206). 게스트 계정도 users 행이 있으므로 그대로 등록된다 — 함께 채우기 주인이 게스트여도 알림을 받는다.
 */
@Service
@RequiredArgsConstructor
public class PushTokenService {

    private final PushTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    /**
     * 토큰을 지금 로그인한 계정에 묶는다. 이미 있는 토큰이면(같은 기기 재등록·다른 계정으로 로그인) 그 행을 이 계정 것으로 옮기고 되살린다.
     *
     * @throws CustomException USER_NOT_FOUND
     */
    @Transactional
    public PushTokenResponse register(Long userId, RegisterPushTokenRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        LocalDateTime now = LocalDateTime.now(clock);
        PushToken token = tokenRepository.findByToken(request.token())
                .map(existing -> {
                    existing.reassign(user, request.platform(), now);
                    return existing;
                })
                .orElseGet(() -> tokenRepository.save(PushToken.builder()
                        .user(user).platform(request.platform()).token(request.token()).lastSeenAt(now).build()));
        return PushTokenResponse.from(token);
    }

    /**
     * 내 토큰 하나를 지운다(로그아웃·알림 끄기). 남의 토큰이거나 없으면 404.
     *
     * @throws CustomException PUSH_TOKEN_NOT_FOUND
     */
    @Transactional
    public void unregister(Long userId, String token) {
        PushToken row = tokenRepository.findByToken(token)
                .filter(t -> t.getUser().getId().equals(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.PUSH_TOKEN_NOT_FOUND));
        tokenRepository.delete(row);
    }
}
