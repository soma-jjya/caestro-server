package com.caestro.server.domain.ops.repository;

/**
 * 운영 요약의 사용자 숫자 12개를 테이블 한 번 통과로 센 결과 (#257). 열 순서는 {@link OpsUserRepository#countSummary} 의 select 순서.
 * 가입 경로별·기기 키 없는 계정은 탈퇴 제외, 탈퇴·전체·신규·활성·폰 수는 탈퇴 포함.
 */
public record OpsUserCounts(long total, long kakao, long google, long apple, long guest, long deleted,
                            long newToday, long newLast7Days, long activeToday, long activeLast7Days,
                            long devices, long withoutDevice) {
}
