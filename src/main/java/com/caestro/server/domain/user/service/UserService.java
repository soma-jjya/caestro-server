package com.caestro.server.domain.user.service;

import com.caestro.server.domain.user.entity.User;
import com.caestro.server.domain.user.repository.UserRepository;
import com.caestro.server.global.exception.CustomException;
import com.caestro.server.global.exception.error.ErrorCode;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    // RefreshToken 저장 키(AuthService와 동일 규칙). 탈퇴 시 삭제하여 재발급을 차단한다.
    private static final String REFRESH_KEY_PREFIX = "refresh:";
    // 탈퇴 즉시 차단용 블랙리스트 키. 기존 access token의 잔여 수명 동안만 유지한다.
    private static final String WITHDRAWN_KEY_PREFIX = "withdrawn:";

    private final UserRepository userRepository;
    private final RedisTemplate<String, String> redisTemplate;

    @Value("${jwt.access-expiration}")
    private long accessExpiration;

    /**
     * 회원 탈퇴 처리.
     * 개인정보를 즉시 익명화(soft delete)하고 토큰을 무효화한다.
     * row 자체의 완전 파기는 하지 않고 익명 상태로 보존하며(FK 정합성·통계 유지),
     * 만료 데이터 정리는 별도 배치가 담당한다.
     *
     * @param userId 탈퇴할 본인 유저 ID
     * @throws CustomException USER_NOT_FOUND - 존재하지 않거나 이미 탈퇴한 계정
     */
    @Transactional
    public void withdraw(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        if (user.isDeleted()) {
            throw new CustomException(ErrorCode.USER_NOT_FOUND);
        }

        // 개인정보 즉시 익명화 + soft delete
        user.withdraw(LocalDateTime.now());

        // RefreshToken 무효화 → 토큰 재발급 차단
        redisTemplate.delete(REFRESH_KEY_PREFIX + userId);

        // 발급된 access token도 즉시 무효화되도록 블랙리스트 등록 (TTL = access token 수명)
        redisTemplate.opsForValue().set(
                WITHDRAWN_KEY_PREFIX + userId,
                "1",
                Duration.ofSeconds(accessExpiration)
        );
    }
}
