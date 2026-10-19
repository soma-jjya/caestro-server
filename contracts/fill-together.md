# 함께 채우기 (Fill together) — API 계약

링크(`https://peakpic.app/fill/{code}`)로 공유한 네컷 프레임 하나를, 서로 다른 폰(앱) 또는 브라우저(웹)에서 각자 채운다.
서버는 프레임과 네 칸의 상태, 사진 바이트만 들고 **합성은 하지 않는다** — 네 칸이 차면 누구든 자기 기기에서 합성한다.
앱은 서버의 OpenAPI 문서(`/v3/api-docs`)에 `/fills`가 있는지 보고 서버 세션으로 전환하므로, 이 경로가 문서에 노출되어야 한다.

## 엔드포인트 (모두 Bearer, 게스트 토큰 포함 · 이미지 GET만 무인증)

| 메서드 | 경로 | 본문 | 응답 |
|---|---|---|---|
| POST | `/fills` | `{frame, layout, artFrameId?, customFrame?, title?, borderless?}` | 201 FillResponse |
| PUT | `/fills/{code}/frame` | `{frame, layout, artFrameId?, customFrame?, borderless?}` 주인만 | 200 FillResponse · 403 주인 아님 (이전 커스텀 요소는 삭제, 새 요소는 frame-assets 로 재업로드) |
| GET | `/fills/{code}` | | 200 FillResponse · 404 없음 · 410 만료 |
| PUT | `/fills/{code}/slots/{i}?name=` | image/jpeg 또는 image/png 바이트 (≤3MB) | 200 FillResponse · 409 남이 채운 칸 · 400 이미지 아님 · 413 초과 |
| DELETE | `/fills/{code}/slots/{i}` | | 200 FillResponse · 403 (본인 칸·주인 아님) |
| PUT | `/fills/{code}/frame-assets/{k}` | image/png 바이트 | 200 `{"url"}` · 403 주인 아님 |
| PUT | `/fills/{code}/gif` | image/gif 바이트 (≤16MB) 주인만 | 200 `{"url"}` · 400 GIF 아님 · 403 주인 아님 · 413 초과 (칸·프레임이 바뀌면 지워짐) |
| DELETE | `/fills/{code}` | | 204 · 403 주인 아님 |
| GET | `/fills/{code}/images/{imageId}` | | 200 이미지 바이트 (무인증, Cache-Control 7일) |

```json
FillResponse = {
  "code": "K7X2MQ", "ownerMe": true,
  "frame": "Black" | "White", "layout": "Strip" | "Grid", "artFrameId": "gyaru" | null,
  "customFrame": {"base": "White", "borderless": false, "layers": [{"cx":0.5,"cy":0.9,"w":0.3,"rot":0,"url":"https://…/images/…","cell":null}]} | null,
  "title": "제주 여행" | null, "borderless": false,
  "slots": [{"index":0,"imageUrl":"https://…"|null,"filledByMe":false,"filledByName":"민지"|null,"filledAt":"2026-09-26T03:00:00Z"|null}, …4개],
  "gifUrl": "https://…/images/…" | null,
  "expiresAt": "2026-10-03T03:00:00Z", "createdAt": "2026-09-26T03:00:00Z"
}
```

규칙: 칸은 먼저 채운 사람 것(본인은 교체 가능), 비우기는 본인 칸 또는 주인, 없애기는 주인만. 시각은 UTC `…Z`.
업로드 3종(slots·frame-assets·gif)은 본문을 읽기 전에 `Content-Length`로 거른다 — 상한 초과 413, 길이 없는(chunked) 요청 411. 바이트 본문에 길이를 붙여 보낼 것.
쓰기 한도(사용자 단위, 초과 시 429): 세션 생성 10/시간, 업로드 3종 합산 30/분, 만료 전 세션 20개(`FILL_TOO_MANY_SESSIONS` — 끝난 세션을 DELETE하면 풀린다). 값은 `fill.ratelimit.*`, `fill.max-active-sessions`.
`name`은 웹 참여자의 표시 이름(선택). 없으면 유저 닉네임, 그것도 없으면 클라이언트가 "친구"로 표시한다.
`name`은 앞뒤 공백을 지우고 40자까지 저장한다(넘치면 자른다, 400이 아니다). 세션 코드는 대소문자를 가리지 않는다 — 모든 경로가 대문자로 맞춰 조회한다.
같은 빈 칸에 동시 요청이 오면 한 명만 200이고 나머지는 409다(칸 행을 잠가 심판한다). 409를 받으면 최신 상태를 다시 그린다.

## 저장과 정리

사진은 `fill_images`(LONGBLOB)에 둔다. 클라이언트가 긴 변 1600px로 줄여 올려 한 장 수백 KB, 세션당 4~6장, 7일 만료.
이 규모에서는 별도 객체 저장소 없이 운영이 가장 단순하다.

바이트의 보관 위치는 `FillImageStore`(domain/fill/store) 하나가 안다. 구현은 설정 `fill.image-store`로 고른다.
- `rds`(기본, 로컬·CI): `fill_images`에 둔다. 이미지 GET은 바이트를 200으로 중계(7일 캐시).
- `s3`(운영): 버킷 `fill.s3.bucket`의 `{fill.s3.key-prefix}/{sessionId}/{imageId}`에 둔다. 이미지 GET은
  존재 확인 뒤 `fill.s3.presign-minutes`(10분) 수명의 서명 주소로 **302**(no-store). 바이트가 앱 서버를 거치지 않는다.
  클라이언트는 리다이렉트를 따라가면 된다(브라우저 `<img>`·OkHttp 기본 동작). 세션 삭제·만료 정리는 세션 접두사를 나열해 지운다.
  **버킷에 CORS가 필요하고, 허용 출처는 `*`여야 한다** — 웹은 사진을 `fetch`하고 캔버스에 그리므로 최종 응답(S3)이
  `Access-Control-Allow-Origin`을 보내야 한다. 그런데 브라우저는 교차 출처 요청이 또 다른 출처로 리다이렉트되면 다음 홉의
  Origin을 `null`로 바꿔 보낸다(표준 동작). `peakpic.app → api.peakpic.app → S3` 두 번째 홉이 그 경우라, 허용 출처를
  `https://peakpic.app`으로 좁히면 `null`과 매칭되지 않아 S3가 200을 줘도 브라우저가 버린다(2026-10-19 전환 직후 실제로 겪음).
  `*`가 안전한 이유: 버킷은 비공개이고 서명 주소(10분)가 있어야만 열리므로 CORS는 접근 권한을 넓히지 않는다.
  설정: AllowedOrigins `*`, AllowedMethods GET·HEAD, AllowedHeaders `*`, ExposeHeaders ETag·Content-Length.
  웹이 `credentials: 'include'`로 부르면 `*`가 거부되므로 이미지 요청은 자격증명 없이 부른다.
서비스와 칸(`fill_slots.image_id`)은 id 문자열만 들고, 주소 형식(`/fills/{code}/images/{id}`)은 두 구현이 같다.
전환은 일회성 교체다 — 전환 시점에 DB에 남아 있던 사진은 더 열리지 않는다(만료·수명 주기로 사라진다).
전환 기준(측정값으로 판단): `fill_images` 합계가 수 GiB에 닿거나, RDS 여유 공간 알람이 울리거나,
이미지 GET이 Grafana의 톰캣 스레드·Hikari에 보이거나, 보존 기간을 7일보다 늘릴 때.
`FillCleanupScheduler`가 매일 04:30(ShedLock) 만료 세션을 칸·이미지와 함께 지운다.

완성된 네컷을 앱이 링크로 공유할 때는 네 칸의 사진에 더해 움직이는 GIF(`PUT /fills/{code}/gif`, 4~7MB)도 같은 테이블에 올리고
`gifUrl`로 내려 준다. 웹은 그걸 보여 주고 내려받게 하며, 앱은 완성 때 사진과 함께 앨범에 넣는다. 칸 사진이나 프레임이 바뀌면 GIF는
더 맞지 않으므로 서버가 지운다.

설정: `fill.public-base-url`(이미지 URL 호스트, 기본 https://api.peakpic.app), `fill.ttl-days`(7), `fill.max-image-bytes`(3MB), `fill.max-gif-bytes`(16MB),
`cleanup.fill.cron`, `cors.allowed-origins`(웹 클라이언트 출처, 기본 peakpic.app).

## DDL (ddl-auto가 update가 아닌 환경용)

```sql
CREATE TABLE fill_sessions (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(12) NOT NULL UNIQUE,
  owner_id BIGINT NOT NULL,
  frame VARCHAR(20) NOT NULL,
  layout VARCHAR(20) NOT NULL,
  art_frame_id VARCHAR(40) NULL,
  custom_frame_json TEXT NULL,
  title VARCHAR(30) NULL,
  borderless TINYINT(1) NOT NULL DEFAULT 0,
  gif_image_id VARCHAR(32) NULL,
  expires_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NULL,
  CONSTRAINT fk_fill_sessions_owner FOREIGN KEY (owner_id) REFERENCES users(id)
);
CREATE TABLE fill_images (
  id VARCHAR(32) PRIMARY KEY,
  session_id BIGINT NOT NULL,
  content_type VARCHAR(40) NOT NULL,
  data LONGBLOB NOT NULL,
  size_bytes INT NOT NULL,
  created_at DATETIME(6) NULL,
  CONSTRAINT fk_fill_images_session FOREIGN KEY (session_id) REFERENCES fill_sessions(id)
);
CREATE TABLE fill_slots (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id BIGINT NOT NULL,
  slot_index INT NOT NULL,
  filled_by_id BIGINT NULL,
  filled_by_name VARCHAR(40) NULL,
  image_id VARCHAR(32) NULL,
  filled_at DATETIME(6) NULL,
  UNIQUE KEY uk_fill_slots_session_index (session_id, slot_index),
  CONSTRAINT fk_fill_slots_session FOREIGN KEY (session_id) REFERENCES fill_sessions(id),
  CONSTRAINT fk_fill_slots_user FOREIGN KEY (filled_by_id) REFERENCES users(id)
);
CREATE INDEX idx_fill_sessions_expires ON fill_sessions(expires_at);
```

`fill_slots.image_id`는 더 이상 `fill_images`를 가리키는 외래키가 아니다 — 바이트가 DB 밖(S3)으로 나가면
그 id가 `fill_images`에 없기 때문이다. **이미 외래키가 걸린 환경(ddl-auto가 지우지 않는다)은 S3 구현을
넣기 전에 직접 떼어야 한다**: `ALTER TABLE fill_slots DROP FOREIGN KEY fk_fill_slots_image;`
(RDS 저장소를 쓰는 동안은 모든 id가 `fill_images`에 있으므로 외래키가 남아 있어도 동작한다.)

## 클라이언트

- Android: `FillSessionApi`/`HttpFillSessionApi` (feat/fourcut-fill-together). 서버에 `/fills`가 없으면 기기 내 데모 세션으로 동작.
- Web: `soma-jjya/peakpic.app` 의 `/fill/{code}` — 게스트 로그인(`POST /auth/guest`) 후 같은 엔드포인트를 브라우저에서 호출.

## 커스텀 프레임 요소(layer)의 `cell`

- `null`: 프레임 위에 얹히는 요소. 칸 위로도 그대로 그려진다.
- `0`~`3`: 그 칸(0부터, 배치 순서) 안에 있는 요소. 칸 밖으로 나가는 부분은 잘려 그려진다.
- `-1`: 프레임의 **배경 사진**. 프레임 모양에 맞게 잘라 둔 이미지로 `cx=0.5, cy=0.5, w=1`이며, 칸 아래에 프레임 전체를 덮어 그린다.

서버는 값을 저장만 하고 그대로 돌려준다(그리는 쪽은 앱과 웹 페이지).
