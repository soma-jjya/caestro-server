package com.caestro.server.global.exception.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    INVALID_TOKEN(401, "유효하지 않은 토큰입니다"),
    TOKEN_EXPIRED(401, "토큰이 만료되었습니다"),
    UNAUTHORIZED(401, "인증이 필요합니다"),
    INVALID_OAUTH_PROVIDER(400, "지원하지 않는 OAuth provider입니다"),
    OAUTH_LOGIN_FAILED(502, "외부 인증 서버 요청에 실패했습니다"),
    MISSING_AUTH_CODE(400, "인가코드가 없습니다"),
    INVALID_REFRESH_TOKEN(401, "유효하지 않은 리프레시 토큰입니다"),
    USER_NOT_FOUND(404, "유저를 찾을 수 없습니다"),
    SESSION_NOT_FOUND(404, "세션을 찾을 수 없습니다"),
    SESSION_ALREADY_CONNECTED(409, "이미 다른 촬영자가 연결된 세션입니다"),
    SESSION_NOT_CONNECTED(409, "두 참여자가 모두 연결된 세션이 아닙니다"),
    SESSION_ACCESS_DENIED(403, "해당 세션에 접근할 권한이 없습니다"),
    SHOT_NOT_FOUND(404, "촬영 결과물을 찾을 수 없습니다"),
    SHOT_ACCESS_DENIED(403, "해당 촬영 결과물에 접근할 권한이 없습니다"),
    INVALID_SHOT_MODE(400, "유효하지 않은 촬영 모드입니다"),
    COLLAB_REQUIRES_SESSION(400, "협업(COLLAB) 모드는 session_id가 필요합니다"),
    SOLO_MUST_NOT_HAVE_SESSION(400, "1인(SOLO) 모드는 session_id를 가질 수 없습니다"),
    INVALID_SORT_PARAMETER(400, "유효하지 않은 정렬 조건입니다"),
    INVALID_SHOT_DIMENSION(400, "해상도(width, height)는 둘 다 있거나 둘 다 없어야 합니다"),
    INVALID_SHOT_LOCATION(400, "위치 정보(latitude, longitude)는 둘 다 있거나 둘 다 없어야 합니다"),
    SUBSCRIPTION_ALREADY_ACTIVE(400, "이미 활성화된 구독이 있습니다"),
    GUEST_ACCOUNT_NOT_ALLOWED(403, "게스트 계정은 결제/구독을 이용할 수 없습니다. 로그인이 필요합니다"),
    SUBSCRIPTION_REQUIRED(403, "프리미엄 구독이 필요합니다"),
    INVALID_SUBSCRIPTION_PLAN(400, "유효하지 않은 구독 플랜입니다"),
    INVALID_SIGNALING_MESSAGE(400, "유효하지 않은 시그널링 메시지입니다"),
    TURN_CREDENTIAL_FAILED(500, "TURN 자격증명 생성에 실패했습니다"),
    INTERNAL_SERVER_ERROR(500, "서버 오류가 발생했습니다");

    private final int status;
    private final String message;

}
