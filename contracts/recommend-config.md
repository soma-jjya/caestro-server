# 포즈추천 알고리즘 설정: 버전 · 게시 · 롤백 (#260)

앱의 포즈추천은 지금 장소 읽기와 점수가 전부 앱에 들어 있다.

- 장소 읽기: Places365 ResNet-18 int8 ONNX(`assets/scene/places365_resnet18_int8.onnx`, 입력 `rgb` 1×3×224×224, 출력 [0] feature 1×512, [1] logits 1×365)
  + 헤드 `assets/scene/place_head.json`(풀 장소 이름 + "기타")
- PlaceTracker 평활: 확신 0.55(이 이상이면 목록을 거르고 장소 칩을 보인다), 반감기 1500ms, 전환 여유 0.10, 오래된 읽기 10000ms, 1000ms 마다 읽기
- 스냅샷 점수: score = bgDist + 25·poseDist (+ 자세가 다르면 25·conf), 목록은 상위 40장(포즈마다 한 장)
- 확신한 장소 X 는 목록을 `장소` 라벨이 X 인 샷으로 거른다

이 값과 헤드·모델을 프레임·배너처럼 스튜디오(관리자 페이지)에서 바꾼다. 설정을 **버전**으로 여러 개 만들고 하나를 **게시**하면 앱이 받는다.
옛 버전을 다시 게시하면 롤백, 게시를 내리면 앱은 번들 기본값으로 돌아간다.

## 인증

- 게시본 `GET /catalog/recommend` — 인증 없음(로그인 전에도 받는다). SecurityConfig permitAll, `/catalog/frames` 와 같다.
- 나머지 `/catalog/studio/recommend/**` — `X-Studio-Key`(ROLE_STUDIO) 또는 ADMIN JWT. 키가 없거나 틀리면 403, 앱 사용자 토큰도 403.

## 게시본 (앱)

```
GET /catalog/recommend   → 200 (ETag, If-None-Match 가 같으면 304, Cache-Control: no-cache)
```

게시본이 없으면 본문은 `{}`. 게시본이 있으면:

```json
{
  "id": "rc_9f2k1x0a3b",
  "name": "기본 + 거리→계단",
  "updatedAt": "2026-11-10T08:00:00Z",
  "params": {
    "place":   { "sure": 0.55, "halfLifeMs": 1500, "switchMargin": 0.1, "staleMs": 10000, "intervalMs": 1000 },
    "scoring": { "bgPoseWeight": 25, "posturePenalty": 25, "listSize": 40 },
    "placeToPool": { "거리": ["거리", "계단"] }
  },
  "head":  { "id": "Xk2…22자", "url": "https://…/catalog/…", "bytes": 81234 },
  "model": null
}
```

- `params` 안의 키는 **모두 선택**이다. 없는 키는 앱 기본값을 쓴다. 서버는 받은 키만 내려준다.
- 숫자는 정수로 떨어지면 정수(`25`, `1500`), 아니면 실수(`0.55`)로 내려간다. `*Ms`·`listSize` 는 늘 정수.
- `head`·`model` 이 `null` 이면 앱 번들 것을 쓴다. `url` 은 다른 카탈로그 그림과 같은 공개 주소(운영은 CDN, 로컬·CI 는
  `https://api.peakpic.app/catalog/images/{id}`)이고 내용이 바뀌지 않는 난수 id 라 오래 캐시해도 된다.
- `updatedAt` 은 **게시한 시각**이다. 게시 중인 버전은 고칠 수 없으므로 내용이 마지막으로 바뀐 때와 같다.
- **ETag** = `W/"rc-{id}-{게시 시각 ms}"`, 게시본이 없으면 `W/"rc-none"`. 게시·같은 버전 다시 게시·롤백(옛 버전 게시)·게시 내리기마다 바뀐다.
  앱은 시작 때 `If-None-Match` 로 묻고 304 면 캐시를 쓴다.

### params

| 키 | 뜻 | 범위 | 앱 기본값 |
|---|---|---|---|
| `place.sure` | 이 확신 이상이면 장소로 목록을 거르고 칩을 보인다 | 0–1 숫자 | 0.55 |
| `place.halfLifeMs` | 읽기 평활 반감기 | 100–60000 정수 | 1500 |
| `place.switchMargin` | 다른 장소로 바꾸는 여유 | 0–1 숫자 | 0.10 |
| `place.staleMs` | 이보다 오래된 읽기는 버린다 | 1000–600000 정수 | 10000 |
| `place.intervalMs` | 장소를 읽는 간격 | 250–10000 정수 | 1000 |
| `scoring.bgPoseWeight` | score = bgDist + **이 값**·poseDist | 0–1000 숫자 | 25 |
| `scoring.posturePenalty` | 자세가 다를 때 + **이 값**·conf | 0–1000 숫자 | 25 |
| `scoring.listSize` | 목록 길이(포즈마다 한 장) | 1–200 정수 | 40 |
| `placeToPool` | 장소 X 를 확신했을 때 추천할 풀 `장소` 라벨들 | 아래 | X 자기 자신만 |

- `placeToPool`: 키 = 헤드가 읽는 장소(헤드의 `classes` 중 하나), 값 = 그 장소일 때 고를 풀 `장소` 라벨 목록. 키 최대 20개,
  값은 1–20개(빈 목록은 400 — 아무것도 추천하지 않게 되므로), 글자는 1–20자. 겹친 값은 한 번만 남는다. 매핑에 없는 장소는 기본(자기 자신).
  서버는 키가 헤드의 `classes` 에 있는지는 보지 않는다(번들 헤드의 장소 목록을 서버가 모른다) — 없는 장소 키는 앱에서 쓰이지 않는다.
- **모르는 키는 400** (`params`·`place`·`scoring` 어디든). 오타가 앱에서 조용히 무시되지 않게 막는다. 값이 `null` 인 키는 없는 것으로 본다.

## 스튜디오 API (X-Studio-Key)

| 메서드 | 경로 | 본문 | 응답 |
|---|---|---|---|
| GET | `/catalog/studio/recommend` | | 200 `{ "activeId": "rc_…" \| null, "items": [Item…] }` 만든 순서 최신부터 |
| POST | `/catalog/studio/recommend` | Upsert | 201 Item (게시되지 않은 채) |
| PUT | `/catalog/studio/recommend/{id}` | Upsert (통째로 교체) | 200 Item · 404 · **409 `RECOMMEND_ACTIVE_LOCKED`** 게시 중 |
| POST | `/catalog/studio/recommend/{id}/publish` | | 200 Item (이 버전만 active, 나머지는 모두 내림, `publishedAt` = 지금) · 404 |
| POST | `/catalog/studio/recommend/unpublish` | | 204 (게시본 없음 → 앱은 번들 기본값. 원래 없어도 204) |
| DELETE | `/catalog/studio/recommend/{id}` | | 204 · 404 · **409 `RECOMMEND_ACTIVE_LOCKED`** 게시 중 |
| POST | `/catalog/studio/recommend/files?kind=head\|model` | 파일 바이트 | 201 `{ "id", "url", "bytes", "kind" }` |

Upsert 본문:

```json
{ "name": "기본 + 거리→계단", "note": "거리에서 계단 샷도 섞는다", "params": { … }, "headId": "…" , "modelId": null }
```

- `name` 1–60자(앞뒤 공백 제거), `note` ≤ 500자(선택, 비면 null), `params` 위 규칙(생략하면 `{}`).
- `headId`·`modelId`: `null`·빈 값이면 앱 번들. 아니면 `files?kind=head`·`files?kind=model` 로 올린 파일 id 여야 한다(종류가 다르거나 없으면 400 `RECOMMEND_INVALID`).
- PUT 은 **통째로 바꾼다**: 보내지 않은 `headId`·`modelId` 는 번들로 돌아간다(스튜디오는 늘 전체를 보낸다).
- **게시 중인 버전은 고치거나 지울 수 없다** — 앱이 이미 받은 내용과 서버 내용이 어긋나지 않게. 스튜디오는 복제(같은 본문으로 POST)해서 고친 뒤 그것을 게시한다.

Item:

```json
{ "id": "rc_9f2k1x0a3b", "name": "…", "note": "…" | null, "params": { … },
  "head": { "id", "url", "bytes" } | null, "model": { "id", "url", "bytes" } | null,
  "active": true, "createdAt": "…Z", "updatedAt": "…Z", "publishedAt": "…Z" | null }
```

- `publishedAt` 은 마지막으로 게시한 시각. 게시를 내리거나 다른 버전을 게시해도 남는다(이력). `active` 는 지금 게시 중인지.
- id 는 `rc_` + `[a-z0-9]` 10자.

### 파일 (헤드 JSON · ONNX 모델)

| kind | Content-Type | 상한 | 검사 |
|---|---|---|---|
| `head` | `application/json` | 5MB | 아래 모양. 틀리면 400 `RECOMMEND_HEAD_INVALID` + 무엇이 틀렸는지 |
| `model` | `application/octet-stream` | 32MB | 비어 있지 않음. 아니면 400 `RECOMMEND_MODEL_INVALID` |

- 본문 크기는 읽기 전에 `Content-Length` 로 거른다(넘으면 413 `RECOMMEND_FILE_TOO_LARGE`, 길이 없으면 411). `kind` 가 head·model 이 아니면 400 `RECOMMEND_INVALID`.
  Content-Type 이 kind 와 맞지 않으면(헤드를 octet-stream 으로 등) 그 kind 의 400.
- 헤드 JSON 모양(앱 `place_head.json` 과 같다. 다른 키는 그대로 둔다):
  - `classes`: 문자열 2–20개, 빈 값·겹친 이름 없음(보통 마지막이 "기타")
  - `places365_bucket`: 정확히 365개 정수, 각각 `0 … classes.size-1`
  - `feature_dim`: 1 이상 정수
  - `weights`: `classes.size` 행, 각 행은 숫자 `feature_dim + classes.size` 개
  - `bias`: 숫자 `classes.size` 개
- 모델 계약: 입력 `rgb` 1×3×224×224(RGB, 0–1), 출력 [feature 1×feature_dim, logits 1×365]. 서버는 ONNX 를 읽지 않는다 —
  앱이 모델을 못 읽거나 헤드와 맞지 않으면(feature_dim 등) 번들 모델·헤드로 돌아간다.
- 저장: 다른 카탈로그 그림과 같이 `catalog_images` 행(폭·높이 0) + `CatalogAssetStore`(운영 S3 + CloudFront, 로컬·CI 는 `catalog_blobs`).
  주소·캐시(30일·immutable)·`GET /catalog/images/{id}` 302/중계도 같다.
- 올리고 하루 안에 어느 버전에도 묶지 않은 파일은 정리 배치(`CatalogCleanupScheduler`, 04:50)가 지운다. 버전이 쓰는 파일은 고아가 아니다.
- 버전을 지우거나 고치면서 헤드·모델을 바꾸면, **다른 버전이 쓰지 않는** 옛 파일은 바로 지운다(복제한 버전이 같은 파일을 쓰면 남긴다).

## 오류

응답 본문은 다른 API 와 같은 `{ "status": 400, "message": "…" }` 이다. 코드 이름은 본문에 없다 — 상태 코드로 나누고 `message` 를 그대로 보인다.
400 은 무엇이 틀렸는지 `기본 문구: 덧붙인 말` 로 내려간다. 관리자는 이 `message` 를 그대로 보인다. 예:

- `추천 설정 값이 올바르지 않습니다: params.place 에 모르는 키가 있습니다: sur (sure·halfLifeMs·switchMargin·staleMs·intervalMs 만)`
- `추천 설정 값이 올바르지 않습니다: params.place.halfLifeMs 는 100~60000 사이 정수여야 합니다 (받은 값: 1500.5)`
- `헤드 JSON 이 올바르지 않습니다: weights[3] 는 숫자 521개(feature_dim 512 + classes 9)여야 합니다 (지금 520개)`

`*Ms`·`listSize` 는 정수로 보내면 되고, `1500.0` 처럼 정수로 떨어지는 실수도 받는다.

| 코드 | 상태 | 언제 |
|---|---|---|
| `RECOMMEND_INVALID` | 400 | name·note·params·headId·modelId·kind 가 틀림 (덧붙인 말 있음) |
| `RECOMMEND_HEAD_INVALID` | 400 | 헤드 JSON 모양·Content-Type (덧붙인 말 있음) |
| `RECOMMEND_MODEL_INVALID` | 400 | 모델이 비었거나 Content-Type 이 octet-stream 이 아님 |
| `RECOMMEND_NOT_FOUND` | 404 | 없는 버전 id |
| `RECOMMEND_ACTIVE_LOCKED` | 409 | 게시 중인 버전을 고치기·지우기 |
| `RECOMMEND_FILE_TOO_LARGE` | 413 | 헤드 5MB·모델 32MB 초과 |
| `LENGTH_REQUIRED` | 411 | 파일 올리기에 Content-Length 없음 |

## 설정

```yaml
catalog:
  recommend:
    max-head-bytes: 5242880
    max-model-bytes: 33554432   # RDS 저장소(로컬·CI)면 catalog_blobs LONGBLOB 이라 max_allowed_packet(기본 64MB)보다 작게
```

## 저장 · DDL

운영은 `ddl-auto: update` 라 새 테이블 `catalog_recommend_configs` 는 배포 때 Hibernate 가 만든다. 다른 테이블은 바뀌지 않는다
(헤드·모델 파일은 기존 `catalog_images` 행). 아래는 ddl-auto 가 update 가 아닌 환경용.

```sql
CREATE TABLE catalog_recommend_configs (
  id           VARCHAR(32)  NOT NULL PRIMARY KEY,
  name         VARCHAR(60)  NOT NULL,
  note         VARCHAR(500) NULL,
  params_json  TEXT         NOT NULL,
  head_id      VARCHAR(32)  NULL,
  model_id     VARCHAR(32)  NULL,
  active       BIT(1)       NOT NULL,
  created_at   DATETIME(6)  NOT NULL,
  updated_at   DATETIME(6)  NOT NULL,
  published_at DATETIME(6)  NULL,
  CONSTRAINT fk_catalog_recommend_head  FOREIGN KEY (head_id)  REFERENCES catalog_images(id),
  CONSTRAINT fk_catalog_recommend_model FOREIGN KEY (model_id) REFERENCES catalog_images(id)
);
```

- 게시는 한 문장 `UPDATE … SET active = false WHERE active = true` 뒤 그 버전만 켠다. 동시에 두 번 게시해도 행 잠금으로 줄을 서 하나만 남고,
  만에 하나 둘이 남아도 게시본·`activeId` 는 나중에 게시한 쪽이다.

## 클라이언트

- 앱: 시작 때(그리고 포즈추천을 열 때) `GET /catalog/recommend` 를 ETag 로 재확인하고 내부 저장소에 캐시한다. `{}` 이거나 못 받으면 번들 기본값.
  `head`·`model` 은 `id` 가 바뀔 때만 내려받아 파일로 캐시하고, 읽기에 실패하면 번들로 돌아간다. 모르는 `params` 키는 무시한다(서버가 이미 막는다).
- 관리자(peakpic.app/admin): 버전 목록(게시 중 표시), 만들기·고치기(게시 중이면 고치기 대신 복제), 게시·롤백(옛 버전 게시)·게시 내리기, 헤드·모델 올리기.
  400 의 `message` 를 그대로 보여 주면 무엇이 틀렸는지 알 수 있다.
