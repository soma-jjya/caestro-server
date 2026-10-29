package com.caestro.server.domain.push.repository;

import com.caestro.server.domain.push.entity.PushToken;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PushTokenRepository extends JpaRepository<PushToken, Long> {

    Optional<PushToken> findByToken(String token);

    /** 보낼 수 있는(꺼지지 않은) 토큰. user_id FK 인덱스를 탄다 */
    List<PushToken> findAllByUserIdAndDisabledAtIsNull(Long userId);
}
