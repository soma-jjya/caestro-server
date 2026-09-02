package com.caestro.server.domain.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 게스트(익명) 유저는 소셜 연동 전이므로 oauth 정보가 없다(null). 결제/로그인 시점에 채워진다.
    @Column
    private String oauthProvider;

    @Column
    private String oauthId;

    // 게스트 유저 식별자. 클라이언트가 생성·보관하는 디바이스 단위 값. 정식 유저는 null이다.
    @Column(unique = true)
    private String deviceId;

    @Column
    private String nickname;

    @Column
    private String profileImage;

    // 애플 로그인 연결 해제(revoke)용 refresh token. 애플 로그인 유저만 값이 있고, 탈퇴 시 통보 후 파기된다.
    @Column(length = 1024)
    private String appleRefreshToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    // 탈퇴(soft delete) 시각. null이면 활성 계정. 유예 후 배치가 이 값을 기준으로 완전 파기한다.
    @Column
    private LocalDateTime deletedAt;

    @Builder
    public User(String oauthProvider, String oauthId, String deviceId, String nickname, String profileImage, Role role) {
        this.oauthProvider = oauthProvider;
        this.oauthId = oauthId;
        this.deviceId = deviceId;
        this.nickname = nickname;
        this.profileImage = profileImage;
        this.role = (role != null) ? role : Role.USER;
    }

    /**
     * 소셜 연동 정보가 없는 게스트(익명) 유저인지 여부.
     */
    public boolean isGuest() {
        return oauthProvider == null;
    }

    /**
     * 게스트 유저를 정식 유저로 승격(계정 연동)한다.
     * userId는 유지되므로 그동안의 세션·촬영 데이터가 그대로 이관된다. (Case A)
     *
     * @param oauthProvider OAuth provider (kakao, google 등)
     * @param oauthId       소셜 고유 ID
     * @param nickname      소셜 닉네임 (있으면 갱신)
     * @param profileImage  소셜 프로필 이미지 (있으면 갱신)
     */
    public void linkOAuth(String oauthProvider, String oauthId, String nickname, String profileImage) {
        this.oauthProvider = oauthProvider;
        this.oauthId = oauthId;
        if (nickname != null) {
            this.nickname = nickname;
        }
        if (profileImage != null) {
            this.profileImage = profileImage;
        }
    }

    /**
     * 애플 refresh token 저장 — 탈퇴 시 애플에 연결 해제(revoke)를 통보하기 위해 보관한다.
     */
    public void updateAppleRefreshToken(String appleRefreshToken) {
        this.appleRefreshToken = appleRefreshToken;
    }

    /**
     * 이미 탈퇴한(soft delete) 계정인지 여부.
     */
    public boolean isDeleted() {
        return deletedAt != null;
    }

    /**
     * 회원 탈퇴 처리.
     * 개인정보 필드를 즉시 익명화(파기)하고 탈퇴 시각을 기록한다.
     * userId(참조 무결성용 뼈대)는 유지되며, 완전 파기(row 삭제)는 유예 후 배치가 수행한다.
     * oauthId를 제거하므로 같은 소셜 계정으로 재로그인하면 신규 유저로 취급된다.
     * appleRefreshToken은 호출 전에 revoke 통보를 마친 뒤 여기서 함께 파기된다.
     *
     * @param deletedAt 탈퇴 시각
     */
    public void withdraw(LocalDateTime deletedAt) {
        this.oauthProvider = null;
        this.oauthId = null;
        this.deviceId = null;
        this.nickname = null;
        this.profileImage = null;
        this.appleRefreshToken = null;
        this.deletedAt = deletedAt;
    }

    public enum Role {
        USER, ADMIN
    }
}
