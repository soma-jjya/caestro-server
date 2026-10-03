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
  "customFrame": {"base": "White", "borderless": false, "layers": [{"cx":0.5,"cy":0.9,"w":0.3,"rot":0,"url":"https://…/images/…"}]} | null,
  "title": "제주 여행" | null, "borderless": false,
  "slots": [{"index":0,"imageUrl":"https://…"|null,"filledByMe":false,"filledByName":"민지"|null,"filledAt":"2026-09-26T03:00:00Z"|null}, …4개],
  "gifUrl": "https://…/images/…" | null,
  "expiresAt": "2026-10-03T03:00:00Z", "createdAt": "2026-09-26T03:00:00Z"
}
```

규칙: 칸은 먼저 채운 사람 것(본인은 교체 가능), 비우기는 본인 칸 또는 주인, 없애기는 주인만. 시각은 UTC `…Z`.
`name`은 웹 참여자의 표시 이름(선택). 없으면 유저 닉네임, 그것도 없으면 클라이언트가 "친구"로 표시한다.

## 저장과 정리

사진은 `fill_images`(LONGBLOB)에 둔다. 클라이언트가 긴 변 1600px로 줄여 올려 한 장 수백 KB, 세션당 4~6장, 7일 만료.
이 규모에서는 별도 객체 저장소 없이 운영이 가장 단순하고, 커지면 `FillImage` 뒤를 S3로 바꾸면 된다(URL 형식 유지).
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
  CONSTRAINT fk_fill_slots_user FOREIGN KEY (filled_by_id) REFERENCES users(id),
  CONSTRAINT fk_fill_slots_image FOREIGN KEY (image_id) REFERENCES fill_images(id)
);
CREATE INDEX idx_fill_sessions_expires ON fill_sessions(expires_at);
```

## 클라이언트

- Android: `FillSessionApi`/`HttpFillSessionApi` (feat/fourcut-fill-together). 서버에 `/fills`가 없으면 기기 내 데모 세션으로 동작.
- Web: `soma-jjya/peakpic.app` 의 `/fill/{code}` — 게스트 로그인(`POST /auth/guest`) 후 같은 엔드포인트를 브라우저에서 호출.
