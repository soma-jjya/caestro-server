package com.caestro.server.domain.ops.repository;

import com.caestro.server.domain.subscription.enums.SubscriptionStatus;
import com.caestro.server.domain.user.entity.User;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 운영 조회 전용 사용자 쿼리. 구독(Subscription)까지 보는 쿼리라 UserRepository 와 떼어 둔다:
 * UserRepository 만 읽는 다른 슬라이스 테스트가 Subscription 엔티티 없이도 뜨도록.
 */
public interface OpsUserRepository extends Repository<User, Long> {

    /**
     * 운영 요약의 사용자 숫자 12개를 테이블 한 번 통과로 센다 (#257).
     * 전에는 count 12번이었고 users 에 인덱스가 없어 12번 다 풀스캔이었다. 조건은 전과 같다:
     * 가입 경로별·기기 키 없는 계정은 탈퇴 제외, 전체·탈퇴·신규·활성·폰 수는 탈퇴 포함. sum 은 행이 없으면 null 이라 coalesce 로 0.
     */
    @Query("""
            select new com.caestro.server.domain.ops.repository.OpsUserCounts(
                count(u),
                coalesce(sum(case when u.deletedAt is null and u.oauthProvider = 'kakao' then 1L else 0L end), 0L),
                coalesce(sum(case when u.deletedAt is null and u.oauthProvider = 'google' then 1L else 0L end), 0L),
                coalesce(sum(case when u.deletedAt is null and u.oauthProvider = 'apple' then 1L else 0L end), 0L),
                coalesce(sum(case when u.deletedAt is null and u.oauthProvider is null then 1L else 0L end), 0L),
                coalesce(sum(case when u.deletedAt is not null then 1L else 0L end), 0L),
                coalesce(sum(case when u.createdAt >= :today then 1L else 0L end), 0L),
                coalesce(sum(case when u.createdAt >= :weekAgo then 1L else 0L end), 0L),
                coalesce(sum(case when u.lastLoginAt >= :today then 1L else 0L end), 0L),
                coalesce(sum(case when u.lastLoginAt >= :weekAgo then 1L else 0L end), 0L),
                count(distinct u.deviceKey),
                coalesce(sum(case when u.deletedAt is null and u.deviceKey is null then 1L else 0L end), 0L))
            from User u
            """)
    OpsUserCounts countSummary(@Param("today") LocalDateTime today, @Param("weekAgo") LocalDateTime weekAgo);

    /**
     * 운영 화면(contracts/studio-ops.md)의 사용자 목록, 최신순 한 페이지. 조건은 null 이면 빠진다.
     * provider 는 kakao·google·apple(탈퇴 제외), guest(소셜 연동 전, 탈퇴 제외), deleted(탈퇴).
     * pro 는 지금 쓸 수 있는 구독(ACTIVE, 만료 전)이 있는지.
     */
    @Query("""
            select u from User u
            where (:cursor is null or u.id < :cursor)
              and (:id is null or u.id = :id)
              and (:nickname is null or lower(u.nickname) like lower(concat('%', :nickname, '%')))
              and (:provider is null
                   or (:provider = 'guest' and u.oauthProvider is null and u.deletedAt is null)
                   or (:provider = 'deleted' and u.deletedAt is not null)
                   or (u.oauthProvider = :provider and u.deletedAt is null))
              and (:pro is null
                   or (:pro = true and exists (select s.id from Subscription s
                        where s.user = u and s.status = :active and s.expiresAt > :now))
                   or (:pro = false and not exists (select s.id from Subscription s
                        where s.user = u and s.status = :active and s.expiresAt > :now)))
            order by u.id desc
            """)
    List<User> findOpsPage(@Param("cursor") Long cursor, @Param("id") Long id, @Param("nickname") String nickname,
                           @Param("provider") String provider, @Param("pro") Boolean pro,
                           @Param("active") SubscriptionStatus active, @Param("now") LocalDateTime now,
                           Pageable pageable);

}
