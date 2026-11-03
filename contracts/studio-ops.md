# 운영 조회: 가입·로그인·구독 현황 (스튜디오)

peakpic.app/studio 의 운영 화면이 사용자 가입 경로, 마지막 로그인, Pro 구독을 보는 **읽기 전용** API.
쓰기(구독 부여·회원 수정)는 없다.

## 인증

- 스튜디오와 같은 `X-Studio-Key` (`catalog.studio-key`). `StudioKeyFilter` 가 `/admin/ops/` 에서도 `ROLE_STUDIO` 를 세운다.
  ADMIN JWT 도 된다. 키가 없거나 틀리면 403, 일반 사용자 토큰도 403.
- 모든 요청은 서버 로그에 한 줄 남는다: `studio-ops access GET /admin/ops/users/12 params=[q, provider] ip=…`
  (검색어 값은 남기지 않고 쓴 파라미터 이름만). 개인정보 접속 기록용.

## 공통

- 시각은 서버의 `LocalDateTime` 문자열로 시간대 표시가 없고 서버 시계(UTC) 기준이다(다른 API와 같다). 화면은 UTC 로 읽어 KST 로 보인다.
  "오늘"은 Asia/Seoul 자정 기준.
- 목록은 최신(id 큰 것)부터, `size` 기본 20 · 최대 50. 응답 `nextCursor` 를 다음 요청 `cursor` 로, null 이면 끝.
- `provider`: `kakao` · `google` · `apple` · `guest`(소셜 연동 전) · `deleted`(탈퇴, 개인정보 파기됨).
- `pro`: 지금 쓸 수 있는 구독(`status = ACTIVE` 이고 `expiresAt` 이 지금 이후) 중 만료가 가장 늦은 것. 없으면 null.
- 구매 토큰(`purchaseToken`)은 어떤 응답에도 넣지 않는다.

## GET /admin/ops/stats

```json
{
  "users": {
    "total": 1234, "kakao": 120, "google": 210, "apple": 40, "guest": 850, "deleted": 14,
    "newToday": 6, "newLast7Days": 41,
    "activeToday": 88, "activeLast7Days": 312
  },
  "subscriptions": { "active": 9, "googlePlay": 5, "appStore": 4, "manual": 0, "autoRenewOff": 2 },
  "generatedAt": "2026-11-04T02:30:00"
}
```

- `total` 은 탈퇴 포함 전체 행. `kakao`·`google`·`apple`·`guest` 는 탈퇴 제외.
- `active*` 는 `lastLoginAt` 기준(로그인·토큰 갱신 때 기록). 이 배포 전에는 기록이 없어 0부터 쌓인다.
- `subscriptions.*` 는 지금 활성인 구독 행 수(사용자 수가 아님). `autoRenewOff` 는 활성 중 자동 갱신을 끈 것(해지 예약).

## GET /admin/ops/users

쿼리: `q` (숫자만이면 id 일치, 아니면 닉네임 부분 일치) · `provider` · `pro` (`true` 활성 구독 있음 / `false` 없음) · `cursor` · `size`

```json
{
  "items": [
    {
      "id": 1201, "provider": "google", "nickname": "이상협",
      "createdAt": "2026-10-20T11:02:03", "lastLoginAt": "2026-11-04T01:57:50",
      "pro": { "store": "GOOGLE_PLAY", "basePlanId": "monthly", "status": "ACTIVE",
               "expiresAt": "2026-12-04T01:58:00", "autoRenewing": true }
    }
  ],
  "nextCursor": 1180
}
```

## GET /admin/ops/users/{id}

목록 한 행에 더해:

```json
{
  "id": 1201, "provider": "google", "nickname": "이상협",
  "createdAt": "…", "lastLoginAt": "…", "deletedAt": null,
  "oauthIdMasked": "108********42", "deviceIdMasked": null, "role": "USER",
  "pushTokens": 1,
  "pro": { … },
  "subscriptions": [
    { "id": 31, "store": "GOOGLE_PLAY", "plan": "PREMIUM", "productId": "peakpic_pro", "basePlanId": "monthly",
      "status": "ACTIVE", "storeState": "SUBSCRIPTION_STATE_ACTIVE", "active": true,
      "startedAt": "…", "expiresAt": "…", "autoRenewing": true, "latestOrderId": "GPA.3312-…",
      "createdAt": "…", "updatedAt": "…" }
  ]
}
```

- `subscriptions` 는 만료가 늦은 것부터 전부.
- 소셜 id·기기 id 는 앞 3자·뒤 2자만 보이고 가운데는 `*` (6자 이하면 전부 `*`).
- 없는 id 는 404 `USER_NOT_FOUND`.

## GET /admin/ops/subscriptions

쿼리: `status` (`ACTIVE`·`EXPIRED`·`CANCELED`) · `store` (`GOOGLE_PLAY`·`APP_STORE`·`MANUAL`) · `cursor` · `size`

```json
{
  "items": [
    { "id": 31, "userId": 1201, "nickname": "이상협", "provider": "google",
      "store": "GOOGLE_PLAY", "plan": "PREMIUM", "productId": "peakpic_pro", "basePlanId": "monthly",
      "status": "ACTIVE", "storeState": "SUBSCRIPTION_STATE_ACTIVE", "active": true,
      "startedAt": "…", "expiresAt": "…", "autoRenewing": true, "latestOrderId": "GPA.3312-…",
      "createdAt": "…", "updatedAt": "…" }
  ],
  "nextCursor": 12
}
```

`status = ACTIVE` 라도 만료가 지난 행은 `active: false` (갱신 알림 전 잠깐).

## 마지막 로그인 (users.last_login_at)

로그인(소셜·게스트)과 토큰 갱신 때 `last_login_at` 을 지금으로 바꾼다. 앱이 열려 있으면 토큰 갱신 주기마다 다시 찍히므로
"마지막으로 앱을 쓴 때"에 가깝다.

```sql
ALTER TABLE users ADD COLUMN last_login_at DATETIME(6) NULL;
```
