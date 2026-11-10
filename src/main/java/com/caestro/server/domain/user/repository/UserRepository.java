package com.caestro.server.domain.user.repository;

import com.caestro.server.domain.user.entity.User;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByOauthProviderAndOauthId(String oauthProvider, String oauthId);

    Optional<User> findByDeviceId(String deviceId);

    /**
     * 로그인·토큰 갱신 때 마지막 로그인 시각, 온 앱, 온 폰(기기 키 해시)을 찍는다. 엔티티를 다시 읽지 않는 한 줄 갱신.
     * platform·deviceKey 가 null(알 수 없는 요청, 헤더 없는 옛 앱)이면 이전 값을 그대로 둔다.
     */
    @Transactional
    @Modifying
    @Query("""
            update User u set u.lastLoginAt = :at, u.lastPlatform = coalesce(:platform, u.lastPlatform),
                u.deviceKey = coalesce(:deviceKey, u.deviceKey)
            where u.id = :id
            """)
    int touchLastLogin(@Param("id") Long id, @Param("at") LocalDateTime at, @Param("platform") String platform,
                       @Param("deviceKey") String deviceKey);
}
