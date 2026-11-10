# 개발자 평가 데이터: 앱 올리기 · 관리자 열람 (#260)

앱의 개발자 모드에서 갤러리에 평가 모드가 생긴다. 사진마다 만족도(1–30)와 가이드 있음/없음(그 사진을 찍을 때 화면에 띄운 가이드에서
자동으로 채우고, 고칠 수 있다)을 매긴다. 개발자가 평가한 사진을 서버로 보내면(사진 + 기록) 팀이 관리자 페이지에서 보고 내려받는다.

**사진은 비공개다(사람 얼굴).** 공개 CDN·카탈로그 자산 저장소(S3 `catalog/`)에 두지 않고 서버 DB 의 전용 테이블(`dev_evaluation_photos`, LONGBLOB)에만 둔다.
읽는 길은 스튜디오 키가 필요한 `GET /admin/dev/evaluations/{id}/photo` 하나뿐이고, 공개 주소는 없다.

## 인증

- 앱 올리기 `/dev/evaluations/**` — `Authorization: Bearer <앱 JWT>`(게스트 포함 모든 설치에 있다). 자기 기록만 쓴다.
  토큰이 없거나 틀리면 403(다른 앱 API 와 같다). 스튜디오 키로는 올릴 수 없다.
- 관리자 `/admin/dev/**` — `X-Studio-Key`(ROLE_STUDIO) 또는 ADMIN JWT. `/admin/ops/**` 와 같다. 키가 없거나 틀리면 403, **앱 사용자 토큰도 403**.
  요청마다 서버 로그에 한 줄 남는다(`studio-ops access GET /admin/dev/evaluations/…/photo params=[] status=200 by=studio ip=…`, 개인정보 접속 기록).

## 앱 올리기

### `PUT /dev/evaluations/{clientId}` — 기록 만들기·덮어쓰기

`clientId` 는 앱이 사진마다 만드는 UUID(`[A-Za-z0-9_-]` 8–64자). 같은 `clientId` 로 다시 보내면 **통째로 덮어쓴다**(사진은 그대로).
주인은 토큰의 사용자. 다른 사용자의 `clientId` 면 403 `DEV_EVAL_FORBIDDEN`.

```json
{
  "rating": 23,              // 필수, 1..ratingMax 정수
  "ratingMax": 30,           // 필수, 2..100 정수
  "guide": true,             // 필수: 가이드를 화면에 띄우고 찍었는지
  "guideVersion": "v012",    // 선택 ≤ 40자
  "guideShot": "shot_0003",  // 선택 ≤ 80자
  "guideStyle": "photo",     // 선택 ≤ 20자 ("photo" 실사 | "line" 라인)
  "shutterScore": 81,        // 선택 0..100 (셔터 순간 포즈 일치도)
  "photoScore": { "total": 78, "pose": 84, "place": 0.97, "shape": 0.96 },  // 선택, 점수 매기기 결과. 각 칸 null 가능
  "mediaType": "photo",      // 필수: photo | fourcut
  "takenAt": "2026-11-10T07:12:00Z",   // 선택 ISO-8601 (+09:00 같은 오프셋도 됨)
  "ratedAt": "2026-11-10T07:30:00Z",   // 필수 ISO-8601
  "width": 3000, "height": 4000,       // 선택 1..100000
  "appVersion": "1.1.0-261110.1639",   // 선택 ≤ 40자
  "device": "SM-S938N",                // 선택 ≤ 80자
  "note": "…",                         // 선택 ≤ 500자
  "extra": { … }                       // 선택 JSON 객체, 직렬화해 4KB 이하. 글로 저장하고 그대로 돌려준다
}
→ 200 { "id": "<clientId>", "hasPhoto": false }
```

- 글자 칸은 앞뒤 공백을 지우고, 비면 null 로 저장한다.
- 사용자당 최대 3000개(`dev-eval.max-per-user`). 넘는 **새** 기록은 409 `DEV_EVAL_LIMIT`. 이미 있는 기록 고치기는 세지 않는다.
- 본문은 읽기 전에 `Content-Length` 로 거른다: 32KB 초과 413 `DEV_EVAL_TOO_LARGE`, 길이 없음 411.
- 값이 틀리면 400 `DEV_EVAL_INVALID` 에 무엇이 틀렸는지 덧붙인다(예: `평가 기록 값이 올바르지 않습니다: rating 은 1~ratingMax(30) 사이 정수여야 합니다`).

### `PUT /dev/evaluations/{clientId}/photo` — 사진

- 본문: 사진 바이트, `Content-Type: image/jpeg`(또는 `image/png`·`image/webp`), 8MB 이하. 앱은 긴 변 1600px JPEG(300–600KB)를 보낸다.
- 기록이 먼저 있어야 한다(없으면 404 `DEV_EVAL_NOT_FOUND`), 남의 기록이면 403 `DEV_EVAL_FORBIDDEN`.
- 형식은 앞 바이트로 확인한다(JPEG `FF D8 FF`, PNG `89 50 4E 47`, WebP `RIFF…WEBP`). 아니면 400 `DEV_EVAL_INVALID_PHOTO`. 저장하는 형식은 앞 바이트로 본 것.
- 이전 사진은 바꾼다(기록당 하나). → 204
- 8MB 초과 413 `DEV_EVAL_PHOTO_TOO_LARGE`(본문을 읽기 전에), 길이 없음 411.

앱 순서: 기록 PUT → `hasPhoto` 가 false 면 사진 PUT. 둘 다 같은 `clientId` 라 몇 번 다시 보내도 결과가 같다.

## 관리자 (X-Studio-Key)

### `GET /admin/dev/evaluations?page=0&size=50&guide=true|false&userId=…`

```json
{ "items": [Row…], "page": 0, "size": 50, "total": 128 }
```

- 최근 고친 것(`updatedAt`)부터. `page` 는 0부터, `size` 기본 50·최대 200. `guide`·`userId` 는 생략하면 거르지 않는다.
- Row = 앱이 보낸 기록 필드 그대로 + 아래:

```json
{ "id": "<clientId>", "userId": 1201, "userNickname": "이상협" | null, "deviceKey": "3f9a0c1d2e4b5a69" | null,
  "rating": 23, "ratingMax": 30, "guide": true, "guideVersion": "v012", "guideShot": "shot_0003", "guideStyle": "photo",
  "shutterScore": 81.0, "photoScore": { "total": 78.0, "pose": 84.0, "place": 0.97, "shape": null } | null,
  "mediaType": "photo", "takenAt": "2026-11-10T07:12:00Z" | null, "ratedAt": "2026-11-10T07:30:00Z",
  "width": 3000, "height": 4000, "appVersion": "…", "device": "SM-S938N", "note": "…", "extra": { … } | null,
  "hasPhoto": true, "photoBytes": 412345 | null, "createdAt": "…Z", "updatedAt": "…Z" }
```

- `deviceKey` 는 운영 화면(contracts/studio-ops.md)과 같은 기기 키 해시의 앞 16자 — 같은 값이면 같은 폰. 기록 전이면 null.
- `shutterScore`·`photoScore` 의 숫자는 실수로 내려간다(81 → `81.0`). `photoScore` 는 네 칸이 모두 비면 null.
- 시각은 모두 ISO-8601 UTC(`Z`). `takenAt`·`ratedAt` 은 앱이 보낸 시각을 UTC 로 맞춘 것, `createdAt`·`updatedAt` 은 서버가 받은 시각.

### `GET /admin/dev/evaluations/{id}/photo`

사진 바이트, `Content-Type` 은 올린 형식(image/jpeg·png·webp), `Cache-Control: private, max-age=300`(브라우저에만 5분, 공용 캐시 없음).
사진이 없으면(기록이 없어도) 404 `DEV_EVAL_PHOTO_NOT_FOUND`. 헤더가 필요해서 `<img src>` 로는 못 연다 — `fetch` 로 받아 blob URL 로 보인다.

### `GET /admin/dev/evaluations/stats`

```json
{ "count": 128, "users": 3, "withPhoto": 120,
  "guide":   { "count": 80, "avgRating": 22.4 },
  "noGuide": { "count": 48, "avgRating": 17.9 },
  "ratingMax": 30 }
```

- `users` 는 올린 서로 다른 사용자 수, `withPhoto` 는 사진이 있는 기록 수.
- `ratingMax` 는 가장 많이 쓴 만점(같으면 큰 값). `avgRating` 은 기록마다 `rating/ratingMax` 로 바꿔 평균한 뒤 `ratingMax` 를 곱한 값(소수 둘째 자리) —
  모두 30점 만점이면 그냥 평균이고, 만점이 섞여도 같은 눈금으로 비교된다. 기록이 없으면 `avgRating`·`ratingMax` 는 null.

### `GET /admin/dev/evaluations.csv?guide=…&userId=…`

- `Content-Type: text/csv;charset=UTF-8`, **UTF-8 BOM** 으로 시작(엑셀이 한국어를 바로 읽는다), 줄 끝 CRLF, 사진은 없다.
- `Content-Disposition: attachment; filename="peakpic-dev-evaluations-YYYYMMDD.csv"`(서울 날짜). CORS 로 이 헤더를 읽을 수 있다. `Cache-Control: no-store`.
- 목록과 같은 `guide`·`userId` 거르기(선택), 정렬도 같다(최근 고친 것부터). 기록마다 한 줄.
- 열(이 순서):

```
id,userId,userNickname,deviceKey,rating,ratingMax,guide,guideVersion,guideShot,guideStyle,
shutterScore,photoScoreTotal,photoScorePose,photoScorePlace,photoScoreShape,mediaType,
takenAt,ratedAt,width,height,appVersion,device,note,extra,hasPhoto,photoBytes,createdAt,updatedAt
```

- 빈 값은 빈 칸. 숫자는 정수로 떨어지면 정수(`81`), `guide`·`hasPhoto` 는 `true`/`false`, 시각은 ISO-8601 UTC, `extra` 는 JSON 글.
- 쉼표·따옴표·줄바꿈이 있는 칸은 `"…"` 로 감싸고 `"` 는 `""`. 글자 칸이 `=` `+` `-` `@` 로 시작하면 앞에 `'` 를 붙인다(엑셀 수식 실행 방지).

### `DELETE /admin/dev/evaluations/{id}`

기록과 사진을 함께 지운다 → 204. 없으면 404 `DEV_EVAL_NOT_FOUND`.

## 오류

응답 본문은 다른 API 와 같은 `{ "status": 409, "message": "…" }` 이다. 코드 이름은 본문에 없다 — 상태 코드로 나누고 `message` 를 보인다.

| 코드 | 상태 | 언제 |
|---|---|---|
| `DEV_EVAL_INVALID` | 400 | 기록 값·clientId 모양이 틀림 (덧붙인 말 있음) |
| `DEV_EVAL_INVALID_PHOTO` | 400 | JPEG/PNG/WebP 가 아님 |
| `DEV_EVAL_FORBIDDEN` | 403 | 다른 사용자의 clientId (토큰 없음도 403 이지만 이 문구가 없다) |
| `DEV_EVAL_NOT_FOUND` | 404 | 기록 없이 사진 올리기, 없는 기록 지우기 |
| `DEV_EVAL_PHOTO_NOT_FOUND` | 404 | 관리자 사진 보기에서 사진이 없음 |
| `DEV_EVAL_LIMIT` | 409 | 사용자당 3000개 초과(새 기록) |
| `LENGTH_REQUIRED` | 411 | Content-Length 없음 |
| `DEV_EVAL_TOO_LARGE` | 413 | 기록 JSON 32KB 초과 |
| `DEV_EVAL_PHOTO_TOO_LARGE` | 413 | 사진 8MB 초과 |

## 설정

```yaml
dev-eval:
  max-per-user: 3000
  max-record-bytes: 32768
  max-photo-bytes: 8388608
```

## 저장 · DDL

- `dev_evaluations`: 기록 한 건 = 한 행, id 는 `clientId`. `user_id` 는 외래키 없이 숫자(사용자 행은 탈퇴해도 남는다). 사진 크기·형식만 여기.
- `dev_evaluation_photos`: 사진 바이트(LONGBLOB), id 는 기록 id. 목록·CSV·통계는 이 테이블을 읽지 않는다.
- 운영은 `ddl-auto: update` 라 두 테이블은 배포 때 Hibernate 가 만든다. 아래는 ddl-auto 가 update 가 아닌 환경용.

```sql
CREATE TABLE dev_evaluations (
  id                 VARCHAR(64)  NOT NULL PRIMARY KEY,
  user_id            BIGINT       NOT NULL,
  rating             INT          NOT NULL,
  rating_max         INT          NOT NULL,
  guide              BIT(1)       NOT NULL,
  guide_version      VARCHAR(40)  NULL,
  guide_shot         VARCHAR(80)  NULL,
  guide_style        VARCHAR(20)  NULL,
  shutter_score      DOUBLE       NULL,
  score_total        DOUBLE       NULL,
  score_pose         DOUBLE       NULL,
  score_place        DOUBLE       NULL,
  score_shape        DOUBLE       NULL,
  media_type         VARCHAR(10)  NOT NULL,
  taken_at           DATETIME(6)  NULL,
  rated_at           DATETIME(6)  NOT NULL,
  width              INT          NULL,
  height             INT          NULL,
  app_version        VARCHAR(40)  NULL,
  device             VARCHAR(80)  NULL,
  note               VARCHAR(500) NULL,
  extra_json         TEXT         NULL,
  photo_bytes        INT          NULL,
  photo_content_type VARCHAR(20)  NULL,
  created_at         DATETIME(6)  NOT NULL,
  updated_at         DATETIME(6)  NOT NULL,
  INDEX idx_dev_evaluations_user (user_id),
  INDEX idx_dev_evaluations_updated (updated_at)
);
CREATE TABLE dev_evaluation_photos (
  id           VARCHAR(64) NOT NULL PRIMARY KEY,
  content_type VARCHAR(20) NOT NULL,
  data         LONGBLOB    NOT NULL
);
```

## 클라이언트

- 앱(개발자 모드 > 갤러리 평가): 평가를 매긴 사진마다 기록 PUT → 사진 PUT. 실패하면 같은 `clientId` 로 다시 보낸다. 409 `DEV_EVAL_LIMIT` 이면 멈추고 알린다.
- 관리자(peakpic.app/admin): 목록(가이드 있음/없음·사용자 거르기, 페이지), 사진 보기(fetch + blob), 요약 숫자, CSV 내려받기, 지우기.
