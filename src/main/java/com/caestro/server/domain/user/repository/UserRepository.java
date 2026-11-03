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

    /** 로그인·토큰 갱신 때 마지막 로그인 시각을 찍는다. 엔티티를 다시 읽지 않는 한 줄 갱신. */
    @Transactional
    @Modifying
    @Query("update User u set u.lastLoginAt = :at where u.id = :id")
    int touchLastLogin(@Param("id") Long id, @Param("at") LocalDateTime at);

    long countByOauthProviderAndDeletedAtIsNull(String oauthProvider);

    long countByOauthProviderIsNullAndDeletedAtIsNull();

    long countByDeletedAtIsNotNull();

    long countByCreatedAtGreaterThanEqual(LocalDateTime since);

    long countByLastLoginAtGreaterThanEqual(LocalDateTime since);
}
