# 함께 채우기 (Fill together) — API 계약

링크(`https://peakpic.app/fill/{code}`)로 공유한 네컷 프레임 하나를, 서로 다른 폰(앱) 또는 브라우저(웹)에서 각자 채운다.
서버는 프레임과 네 칸의 상태, 사진 바이트만 들고 **합성은 하지 않는다** — 네 칸이 차면 누구든 자기 기기에서 합성한다.
앱은 서버의 OpenAPI 문서(`/v3/api-docs`)에 `/fills`가 있는지 보고 서버 세션으로 전환하므로, 이 경로가 문서에 노출되어야 한다.

## 엔드포인트 (모두 Bearer, 게스트 토큰 포함 · 이미지 GET만 무인증)

| 메서드 | 경로 | 본문 | 응답 |
|---|---|---|---|
| POST | `/fills` | `{frame, layout, artFrameId?, customFrame?, title?, borderless?}` | 201 FillResponse |
| PUT | `/fills/{code}/frame` | `{frame, layout, artFrameId?, customFrame?, borderless?}` 주인만 | 200 FillResponse · 403 주인 아님 (이전 커스텀 요소는 삭제, 새 요소는 frame-assets 로 재업로드) |
| GET | `/fills/{code}` | 헤더 `If-None-Match: <직전 ETag>` 선택 | 200 FillResponse(+ `ETag`) · **304 변경 없음(본문 없음)** · 404 없음 · 410 만료 |
| PUT | `/fills/{code}/slots/{i}?name=` | image/jpeg 또는 image/png 바이트 (≤3MB) 누구나, 채워진 칸도 | 200 FillResponse (이전 사진은 `queue`로) · 400 이미지 아님 · 413 초과 |
| PUT | `/fills/{code}/slots/{i}/clip` | image/gif 바이트 (≤6MB) 그 칸을 채운 본인만 | 200 FillResponse · 400 GIF 아님·빈 칸 · 403 채운 사람 아님 · 413 초과 (사진이 바뀌거나 칸을 비우면 지워짐) |
| DELETE | `/fills/{code}/slots/{i}` | 누구나 | 200 FillResponse (사진은 `queue`로) |
| DELETE | `/fills/{code}/queue/{id}` | 누구나 | 200 FillResponse · 404 대기열에 없음 (사진·클립 바이트 삭제) |
| POST | `/fills/{code}/queue/{id}/restore?slot=i` | 누구나 | 200 FillResponse · 400 칸 번호 · 404 대기열에 없음 (그 칸의 사진은 `queue` 맨 아래로) |
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
  "slots": [{"index":0,"imageUrl":"https://…"|null,"filledByMe":false,"filledByName":"민지"|null,"filledAt":"2026-09-26T03:00:00Z"|null,"clipUrl":"https://…"|null}, …4개],
  "gifUrl": "https://…/images/…" | null,
  "expiresAt": "2026-10-03T03:00:00Z", "createdAt": "2026-09-26T03:00:00Z",
  "queue": [{"id":17,"imageUrl":"https://…","clipUrl":"https://…"|null,"fromSlot":2,"filledByMe":false,"filledByName":"민지"|null,"filledAt":"…Z"|null,"queuedAt":"…Z"}, …]
}
```

규칙(2026-10-21, #fill-queue): 칸은 **누구나** 채우고·바꾸고·비울 수 있다. 바뀌거나 비워진 사진은 지워지지 않고 세션의 **대기열(`queue`)** 맨 아래로
내려간다(클립·주인·이름과 함께) — 다른 참여자가 "아까 사진이 이랬지"를 볼 수 있다. 대기열의 사진은 누구나 어느 칸으로든 되돌리거나(`restore`,
그 칸에 있던 사진은 대기열 맨 아래로) 지울 수 있다(`DELETE queue/{id}`, 바이트까지). 대기열은 세션당 `fill.queue-max`(12)개까지이고 넘치면
가장 오래된 것이 사라진다. 없애기는 주인만. 시각은 UTC `…Z`. 409(`FILL_SLOT_TAKEN`)는 더 내려가지 않는다 — 옛 클라이언트의 409 처리는 그대로 두어도 된다.
`queue`를 모르는 클라이언트는 필드를 무시하면 된다(웹·iOS는 아직 대기열을 그리지 않는다).
칸 클립(`clipUrl`)은 앱 안 카메라로 칸을 채울 때 사진과 함께 찍힌 짧은 움직임(셔터 전 약 2초)이다. 사진을 먼저 올리고(PUT slots/{i}) 이어서 클립을 붙인다.
완성 네컷의 움직이는 GIF는 서버가 합성하지 않는다 — 네 칸이 차면 기기가 각 칸의 클립(없으면 사진)으로 만든다. 웹은 칸 자리에서 클립을 재생한다. 클립을 모르는 클라이언트는 이 필드를 무시해도 된다.
**세션 조회와 폴링 (#205).** 주인이 세션 화면을 보는 동안 앱·웹은 `GET /fills/{code}` 를 3~5초마다 다시 묻는다. 응답에는 `ETag` 가 있고
(응답 JSON 의 해시 — 칸·대기열·프레임·GIF·이름 어느 것이 바뀌어도 달라진다. 보는 사람에 따라 `ownerMe`·`filledByMe` 가 달라 ETag 도 사람마다 다르다),
직전 ETag 를 `If-None-Match` 로 보내면 그대로일 때 **304 + 본문 없음**이다. `Cache-Control: no-cache` 라 매번 서버에 묻되 바뀐 것만 받는다.
화면을 떠나면 폴링을 멈춘다. 푸시가 포그라운드에서 오면 배너 대신 즉시 한 번 다시 묻는다.
다음 단계는 SSE(인스턴스가 둘이라 Redis pub/sub 팬아웃 필요)이고, 계기는 `/fills/{code}` 요청이 전체 요청에서 차지하는 비중과 그 DB 시간이 지표에 보일 때다
(`http_server_requests_seconds{uri="/fills/{code}",status}` 의 200 대 304 비율로 본다). 서버 쪽 DB 비용을 먼저 줄여야 하면 세션에 version 카운터를 두는 것이 그 전 단계다.

업로드 3종(slots·frame-assets·gif)은 본문을 읽기 전에 `Content-Length`로 거른다 — 상한 초과 413, 길이 없는(chunked) 요청 411. 바이트 본문에 길이를 붙여 보낼 것.
쓰기 한도(사용자 단위, 초과 시 429): 세션 생성 10/시간, 업로드 3종 합산 30/분, 만료 전 세션 20개(`FILL_TOO_MANY_SESSIONS` — 끝난 세션을 DELETE하면 풀린다). 값은 `fill.ratelimit.*`, `fill.max-active-sessions`.
`name`은 웹 참여자의 표시 이름(선택). 없으면 유저 닉네임, 그것도 없으면 클라이언트가 "친구"로 표시한다.
`name`은 앞뒤 공백을 지우고 40자까지 저장한다(넘치면 자른다, 400이 아니다). 세션 코드는 대소문자를 가리지 않는다 — 모든 경로가 대문자로 맞춰 조회한다.
같은 빈 칸에 동시 요청이 오면 칸 행을 잠가 차례로 처리한다 — 둘 다 200이고 뒤의 것이 칸을, 앞의 것이 대기열을 갖는다. 응답의 최신 상태를 다시 그린다.

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
전환은 일회성 교체다 — 전환 시점에 DB에 남아 있던 사진은 더 열리지 않는다.
**단, 옛 `fill_images` 행은 저절로 사라지지 않는다.** S3 저장소의 세션 삭제는 S3만 지우는데 `fill_images.session_id`가
`fill_sessions`를 외래키로 참조하므로, 옛 행이 남은 세션은 만료 정리에서 세션 삭제가 외래키 위반으로 실패한다
(2026-10-20 실제 발생, #162). 저장소를 바꿀 때는 쓰기·읽기뿐 아니라 **삭제 경로가 옛 저장소의 외래키와 얽혀 있지 않은지** 확인한다.
전환 후에는 `DELETE FROM fill_images`로 비우고, 안정화 뒤 테이블을 DROP한다.
전환 기준(측정값으로 판단): `fill_images` 합계가 수 GiB에 닿거나, RDS 여유 공간 알람이 울리거나,
이미지 GET이 Grafana의 톰캣 스레드·Hikari에 보이거나, 보존 기간을 7일보다 늘릴 때.
`FillCleanupScheduler`가 매일 04:30(ShedLock) 만료 세션을 칸·이미지와 함께 지운다. 세션마다 트랜잭션을 따로 걸어 하나가 실패해도 나머지는 지워지고, 실패는 `fill.cleanup.failed` 지표와 warn 로그로 남는다(#162). 종료 로그 `Fill cleanup batch end (purged=N, failed=M)`.

완성된 네컷을 앱이 링크로 공유할 때는 네 칸의 사진에 더해 움직이는 GIF(`PUT /fills/{code}/gif`, 4~7MB)도 같은 테이블에 올리고
`gifUrl`로 내려 준다. 웹은 그걸 보여 주고 내려받게 하며, 앱은 완성 때 사진과 함께 앨범에 넣는다. 칸 사진이나 프레임이 바뀌면 GIF는
더 맞지 않으므로 서버가 지운다.

설정: `fill.public-base-url`(이미지 URL 호스트, 기본 https://api.peakpic.app), `fill.ttl-days`(7), `fill.max-image-bytes`(3MB), `fill.max-gif-bytes`(16MB), `fill.queue-max`(12),
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
  clip_id VARCHAR(32) NULL,
  filled_at DATETIME(6) NULL,
  UNIQUE KEY uk_fill_slots_session_index (session_id, slot_index),
  CONSTRAINT fk_fill_slots_session FOREIGN KEY (session_id) REFERENCES fill_sessions(id),
  CONSTRAINT fk_fill_slots_user FOREIGN KEY (filled_by_id) REFERENCES users(id)
);
CREATE TABLE fill_queue (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id BIGINT NOT NULL,
  from_slot INT NOT NULL,
  filled_by_id BIGINT NULL,
  filled_by_name VARCHAR(40) NULL,
  image_id VARCHAR(32) NOT NULL,
  clip_id VARCHAR(32) NULL,
  filled_at DATETIME(6) NULL,
  queued_at DATETIME(6) NOT NULL,
  KEY idx_fill_queue_session (session_id),
  CONSTRAINT fk_fill_queue_session FOREIGN KEY (session_id) REFERENCES fill_sessions(id),
  CONSTRAINT fk_fill_queue_user FOREIGN KEY (filled_by_id) REFERENCES users(id)
);
CREATE INDEX idx_fill_sessions_expires ON fill_sessions(expires_at);
```

대기열 이전에 만든 환경: 위 `fill_queue` CREATE만 더하면 된다(ddl-auto update면 저절로 생긴다).

칸 클립 이전에 만든 환경: `ALTER TABLE fill_slots ADD COLUMN clip_id VARCHAR(32) NULL;` (ddl-auto update면 저절로 붙는다)

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
