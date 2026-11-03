# B2B 문의 접수 (#216)

B2B 메타 광고(카페·매장, 축제·행사, 관광공사)의 도착지인 문의 폼이 보내는 요청과, 관리자 페이지(`peakpic.app/admin`)가 읽는 API 의 계약이다.
서버는 저장·한도·관리 API 까지만 한다. 폼 페이지(landing 레포)와 관리자 화면(peakpic-b2b-admin 레포), PostHog·Meta 이벤트는 각 레포 몫이다.

운영 API 기준 주소: `https://api.peakpic.app`.

## 경로와 책임

| 구분 | 웹 | 백엔드 API | 인증 |
|---|---|---|---|
| 문의 폼 | 랜딩 `peakpic.app/b2b/`(예정) | `POST /inquiries` | 없음. CORS 는 `peakpic.app` |
| 관리자 | `peakpic.app/admin/inquiries`(예정) | `GET/PATCH /b2b/admin/inquiries/**` | 기존 `X-Studio-Key` 또는 ADMIN JWT (B2B 프로젝트 관리와 동일) |

관리자 FE 는 기존 `/admin/api/projects` 프록시와 같은 방식으로 `/admin/api/inquiries` 를 두고, 서버 쪽에서만 키를 붙인다. 브라우저에 키를 내리지 않는다.

## 접수 `POST /inquiries`

요청 `Content-Type: application/json`

| 필드 | 필수 | 규칙 |
|---|---|---|
| `type` | 필수 | `CAFE` / `FESTIVAL` / `TOURISM` / `OTHER`. 폼은 `utm_content` 가 `b2b_cafe…` 면 CAFE, `b2b_festival…` 면 FESTIVAL 을 기본으로 고른다 |
| `organization` | 필수 | 업체·행사명, 1~80자 |
| `contactName` | 필수 | 담당자 성함, 1~40자 |
| `phone` | 필수 | `^[0-9+\-\s()]{8,20}$` |
| `email` | 선택 | 이메일 형식, ≤120자 |
| `region` | 선택 | 시·도, ≤40자 |
| `preferredPeriod` | 선택 | 희망 시기, ≤80자 |
| `scale` | 선택 | 규모, ≤80자 |
| `website` | 선택 | 인스타그램·홈페이지, ≤200자 |
| `message` | 선택 | 문의 내용, ≤500자 |
| `privacyAgreed` | 필수 | `true` 가 아니면 400 |
| `marketingAgreed` | 선택 | 기본 false |
| `utm` | 선택 | `{ "source", "medium", "campaign", "content", "term" }` 각 ≤100자. 주소창의 `utm_*` 를 그대로 |
| `landingPath` | 선택 | 제출한 페이지 경로(예: `/b2b/`), ≤200자 |
| `fax` | 허니팟 | 화면에는 없는 숨은 input. 값이 있으면 저장하지 않고 201 `{ "id": 0 }` |

빈 문자열은 null 로 저장한다. 서버가 `clientIp`(X-Forwarded-For 첫 값), `userAgent`(300자에서 자름), `createdAt` 을 채운다.

응답

- 201 `{ "id": 123 }`
- 400 `{ "status": 400, "message": "연락처는 필수입니다" }` — 필수 누락·형식·동의 없음. 메시지는 쉼표로 이어진다
- 429 `{ "status": 429, "message": "요청이 너무 잦습니다. 잠시 후 다시 시도해주세요" }` — IP 당 `inquiry.ratelimit.per-ip`(기본 5)/`per-ip-window-seconds`(3600), 전체 `daily`(300)/일. Redis 고정 창

예시

```
curl -X POST https://api.peakpic.app/inquiries -H 'Content-Type: application/json' -d '{
  "type": "CAFE", "organization": "디저트 카페", "contactName": "김담당", "phone": "010-1234-5678",
  "email": "owner@example.com", "region": "서울", "preferredPeriod": "12월 초", "message": "가게 이름 넣은 프레임 받고 싶어요",
  "privacyAgreed": true, "marketingAgreed": false,
  "utm": { "source": "meta", "medium": "paid_social", "campaign": "b2b_1102", "content": "b2b_cafe", "term": "feed" },
  "landingPath": "/b2b/"
}'
```

## 관리 `/b2b/admin/inquiries`

| 메서드·경로 | 설명 |
|---|---|
| `GET /?status&type&cursor&size` | 최신순(id 내림차순). `status`·`type` 생략 시 전체. `size` 기본 20 최대 50. 응답 `{ "items": [...], "nextCursor": 123 \| null }` — `nextCursor` 를 다음 요청의 `cursor` 로 |
| `GET /count?status=NEW` | `{ "count": 3 }`. `status` 생략 시 전체 개수. 탭 배지용 |
| `GET /{id}` | 한 건. 없으면 404 `INQUIRY_NOT_FOUND` |
| `PATCH /{id}` | `{ "status"?, "adminMemo"?, "linkedProjectId"? }` 보낸 것만 바꾼다. 응답은 바뀐 한 건 |

항목 모양

```json
{
  "id": 123, "type": "CAFE", "organization": "디저트 카페", "contactName": "김담당", "phone": "010-1234-5678",
  "email": null, "region": "서울", "preferredPeriod": "12월 초", "scale": null, "website": null, "message": "…",
  "privacyAgreed": true, "marketingAgreed": false,
  "utm": { "source": "meta", "medium": "paid_social", "campaign": "b2b_1102", "content": "b2b_cafe", "term": "feed" },
  "landingPath": "/b2b/", "clientIp": "203.0.113.9", "userAgent": "Mozilla/5.0 …",
  "status": "NEW", "adminMemo": null, "linkedProjectId": null, "handledAt": null,
  "createdAt": "2026-11-02T12:00:00", "updatedAt": "2026-11-02T12:00:00"
}
```

시각은 UTC, 초 단위 ISO(오프셋 표기 없음) — 알림함과 같다.

### 상태

`NEW → CONTACTED → PROPOSAL_SENT → WON / LOST`, 그리고 `SPAM`. 어느 상태로든 바꿀 수 있다(영업 메모라 상태 기계를 강제하지 않는다).
`NEW` 를 처음 벗어나는 순간 `handledAt` 이 남고 이후 바뀌지 않는다(응답 속도 지표).

`linkedProjectId` 는 계약 뒤 만든 B2B 프로젝트의 id(`b2b_projects.id`). 없는 id 면 404 `B2B_PROJECT_NOT_FOUND`, 빈 문자열이면 해제.
이 고리로 `utm_content`(소재)별 문의 → 계약 전환을 SQL 로 센다. FK 는 걸지 않는다(프로젝트를 지워도 문의 기록은 남긴다).

삭제 API 는 없다. 스팸·보류는 상태로 표시한다. 보관 기간이 정해지면 정리 배치를 따로 둔다.

## 저장 순서와 알림

1. 한도(IP 창 → 전체 하루 창) → 2. 허니팟 → 3. 저장(한 트랜잭션) → 4. 커밋 뒤 비동기로 웹훅.

`inquiry.webhook-url` 이 비어 있지 않으면 접수 커밋 뒤 `@Async @TransactionalEventListener(AFTER_COMMIT)` 가 Slack/Discord 호환 웹훅에
`{ "text": "...", "content": "..." }`(Slack 은 text, Discord 는 content 를 읽는다)로 한 줄을 보낸다:
`[B2B 문의 #123] CAFE · 디저트 카페 · 김담당 · 010-1234-5678 · 소재 b2b_cafe` + 줄바꿈 + `{inquiry.admin-url}/123`.
실패해도 접수에는 영향이 없다(지표 `inquiry.webhook.failed`, 로그). URL 은 비밀값이라 Secrets Manager 에 두고 로그에 찍지 않는다.

## 지표

| 이름 | 태그 | 뜻 |
|---|---|---|
| `inquiry.received` | `type` | 저장된 문의 수 |
| `inquiry.rejected` | `reason=rate_limit\|honeypot` | 한도·허니팟에 걸린 수. 정상 시간대에 `rate_limit` 이 찍히면 한도 오탐 신호 |
| `inquiry.webhook.failed` | | 웹훅 실패 수 |

## 설정 (application.yml `inquiry.*`)

| 키 | 기본 | 뜻 |
|---|---|---|
| `ratelimit.per-ip` | 5 | IP 당 창 내 허용 수 |
| `ratelimit.per-ip-window-seconds` | 3600 | IP 창 길이 |
| `ratelimit.daily` | 300 | 전체 하루 상한 |
| `webhook-url` | 빈 값 | 비면 웹훅 끔 |
| `admin-url` | `https://peakpic.app/admin/inquiries` | 웹훅 글의 관리자 링크 |

## DDL

[216-b2b-inquiries.sql](sql/216-b2b-inquiries.sql). 배포 전에 운영 DB 에 한 번 적용한다.

## 하지 않는 것

파일 첨부, 자동 회신 메일, 보관 기간 정리 배치, CAPTCHA(스팸이 실제로 보이면 Turnstile 을 붙인다).
