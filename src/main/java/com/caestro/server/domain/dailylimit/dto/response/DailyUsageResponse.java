package com.caestro.server.domain.dailylimit.dto.response;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 오늘의 한도 상태.
 *
 * @param allowed   (조회) 지금 하나 더 쓸 수 있는가 / (사용) 이번 요청이 허용됐는가(이미 센 키면 true)
 * @param unlimited 프리미엄 구독 중이라 한도가 없다 (이때 limit·used·remaining 은 null)
 * @param date      한도를 세는 날 (limits.zone 기준)
 * @param resetsAt  다음 날로 넘어가는 시각
 */
public record DailyUsageResponse(
        boolean allowed,
        boolean unlimited,
        Integer limit,
        Integer used,
        Integer remaining,
        LocalDate date,
        OffsetDateTime resetsAt
) {

    public static DailyUsageResponse counted(boolean allowed, int limit, int used, LocalDate date, OffsetDateTime resetsAt) {
        return new DailyUsageResponse(allowed, false, limit, used, Math.max(0, limit - used), date, resetsAt);
    }

    public static DailyUsageResponse unlimited(LocalDate date, OffsetDateTime resetsAt) {
        return new DailyUsageResponse(true, true, null, null, null, date, resetsAt);
    }
}
