# 카탈로그: AI 네컷 프레임 · 홈 배너 (디자이너 스튜디오)

디자이너가 peakpic.app/studio 에서 올린 일러스트 프레임과 홈 상단 배너를 앱·웹이 내려받는다.
앱에 번들된 기본 프레임(gyaru, street, chrome, birthday, picnic, patchwork, vintage)은 그대로 두고,
그 뒤에 서버 프레임이 이어진다. 세션(`fills.artFrameId`)은 문자열 id 를 들고 있으므로 계약 변경이 없다.

## 인증

- 공개 읽기(인증 없음, CORS는 fills 와 같이 peakpic.app): `GET /catalog/frames`, `GET /catalog/frames/{id}`,
  `GET /catalog/banners`, `GET /catalog/images/{imageId}`
- 스튜디오(쓰기, 숨김 포함 읽기): 헤더 `X-Studio-Key: <catalog.studio-key>` 가 일치하면 `ROLE_STUDIO`.
  경로 `/catalog/studio/**` 는 이 역할(또는 ADMIN JWT)만. 키가 설정되지 않으면(빈 값) 스튜디오 전체가 403.
  MetricsTokenFilter 와 같은 정적 키 필터(상수 시간 비교). 키는 로그·응답에 남기지 않는다.

## 이미지

- `POST /catalog/studio/images` — raw body, `Content-Type: image/png | image/jpeg`, ≤ 8MB
  → 201 `{"imageId":"…22자…","url":"https://api.peakpic.app/catalog/images/{id}","width":1062,"height":1481,"contentType":"image/png"}`
  폭·높이는 서버가 ImageIO 로 읽는다. 읽히지 않으면 400 `CATALOG_INVALID_IMAGE`.
- `GET /catalog/images/{imageId}` → 바이트, `Cache-Control: public, max-age=2592000` (id 가 난수이고 내용이 안 바뀜). 404 `CATALOG_IMAGE_NOT_FOUND`.
- 저장: `catalog_images` LONGBLOB (fill_images 와 같은 방식, 프레임은 수십 장 규모). 프레임·배너가 지워지면 그 이미지도 지운다.
  어디서도 안 쓰는 이미지(올리고 저장을 안 한 것)는 하루 지나면 스케줄이 정리한다.

## 프레임

```
GET /catalog/frames  → 200 (ETag 지원: If-None-Match 일치 시 304)
{
  "updatedAt": "2026-10-22T06:00:00Z",          // 목록 중 가장 최근 updatedAt (없으면 null)
  "frames": [
    {
      "id": "af_x7k2p9q1ab",                      // "af_" + 10자 [a-z0-9], 서버 생성
      "title":    { "ko": "봄 소풍", "en": "Spring picnic" },
      "subtitle": { "ko": "들판 위 네컷", "en": "Four cuts in a field" },   // en 이 비면 ko 를 복사해 내려준다
      "imageUrl": "https://api.peakpic.app/catalog/images/…",
      "width": 1062, "height": 1481,
      "windows": [                                 // 정확히 4개, 앱 순서 TL·TR·BL·BR 로 디자이너가 배치
        { "x": 0.049, "y": 0.0236, "w": 0.4369, "h": 0.3842, "rot": 0 },
        …
      ],                                           // x,y,w,h 는 이미지 비율(0..1, 회전 전 사각형), rot 는 도(°), 사각형 중심 기준 시계방향 -180..180
      "sortOrder": 10, "hidden": false,
      "createdAt": "…", "updatedAt": "…"
    }
  ]
}
```
- 숨김(`hidden`)은 목록에서 빠진다. 스튜디오 키로 `?all=true` 를 붙이면 숨김도 온다(스튜디오 목록).
- 정렬: `sortOrder` 오름차순, 같으면 `createdAt` 오름차순.
- `GET /catalog/frames/{id}` → 숨김이어도 200 (세션·미러링이 참조 중일 수 있다). 없으면 404 `CATALOG_FRAME_NOT_FOUND`.
- `POST /catalog/studio/frames` 본문 `{ "title":{"ko","en"?}, "subtitle":{"ko","en"?}, "imageId", "windows":[4], "sortOrder"?, "hidden"? }` → 201 프레임
- `PUT /catalog/studio/frames/{id}` 같은 필드, 부분 갱신(보낸 것만 바뀐다; `imageId` 를 바꾸면 이전 이미지는 지운다) → 200
- `DELETE /catalog/studio/frames/{id}` → 204 (이미지도 삭제)
- 검증(400 `CATALOG_INVALID_FRAME`): title.ko 1..40자, subtitle.ko ≤ 80자, windows 정확히 4개, 0 ≤ x,y, w,h > 0, x+w ≤ 1.001, y+h ≤ 1.001, rot -180..180, imageId 존재.

## 배너 (홈 상단)

```
GET /catalog/banners → 200 (ETag)
{
  "updatedAt": "…",
  "banners": [
    {
      "id": "hb_k2m9x1p0qz",
      "title": { "ko": "포즈 고민 없이,\n가이드 따라 찰칵!", "en": "…" },
      "body":  { "ko": "피크픽이 직접 추천하는 인생샷\n포즈로 누구나 쉽게 촬영해요.", "en": "…" },
      "chip":  { "ko": "포즈추천", "en": "Pose guide" },    // 선택. 비면 칩 없음
      "imageUrl": "…", "width": 1080, "height": 780,
      "action": "poseGuide",      // 선택: poseGuide | fourCut | fillTogether | frameLibrary | url | null(동작 없음)
      "linkUrl": null,            // action=url 일 때 여는 주소
      "sortOrder": 0, "hidden": false, "createdAt": "…", "updatedAt": "…"
    }
  ]
}
```
- `POST /catalog/studio/banners` `{ title{ko,en?}, body{ko,en?}?, chip{ko,en?}?, imageId, action?, linkUrl?, sortOrder?, hidden? }` → 201
- `PUT /catalog/studio/banners/{id}` 부분 갱신 → 200, `DELETE …/{id}` → 204 (이미지도 삭제)
- 검증(400 `CATALOG_INVALID_BANNER`): title.ko 1..60자, body.ko ≤ 120자, chip.ko ≤ 20자, action 은 위 값 중 하나, action=url 이면 linkUrl 은 https 주소.
- 앱: 목록이 비거나 못 받으면 번들된 기본 배너 하나를 보인다. 2개 이상이면 자동으로 넘어간다(5초).

## 오류

`CATALOG_FRAME_NOT_FOUND(404)`, `CATALOG_BANNER_NOT_FOUND(404)`, `CATALOG_IMAGE_NOT_FOUND(404)`,
`CATALOG_INVALID_IMAGE(400)`, `CATALOG_INVALID_FRAME(400)`, `CATALOG_INVALID_BANNER(400)`, `CATALOG_IMAGE_TOO_LARGE(413)`.

## 설정

```yaml
catalog:
  studio-key: ""            # 비면 스튜디오(쓰기) 전체 403. 운영은 prod yml/Secret
  max-image-bytes: 8388608
  public-base-url: https://api.peakpic.app
```

## DDL (ddl-auto 가 update 가 아닌 환경용)

```sql
CREATE TABLE catalog_images (
  id VARCHAR(32) PRIMARY KEY,
  content_type VARCHAR(40) NOT NULL,
  data LONGBLOB NOT NULL,
  size_bytes INT NOT NULL,
  width INT NOT NULL,
  height INT NOT NULL,
  created_at DATETIME(6) NOT NULL
);
CREATE TABLE catalog_frames (
  id VARCHAR(32) PRIMARY KEY,
  title_ko VARCHAR(40) NOT NULL,
  title_en VARCHAR(60) NULL,
  subtitle_ko VARCHAR(80) NULL,
  subtitle_en VARCHAR(120) NULL,
  image_id VARCHAR(32) NOT NULL,
  width INT NOT NULL,
  height INT NOT NULL,
  windows_json TEXT NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  hidden TINYINT(1) NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT fk_catalog_frames_image FOREIGN KEY (image_id) REFERENCES catalog_images(id)
);
CREATE TABLE catalog_banners (
  id VARCHAR(32) PRIMARY KEY,
  title_ko VARCHAR(60) NOT NULL,
  title_en VARCHAR(90) NULL,
  body_ko VARCHAR(120) NULL,
  body_en VARCHAR(180) NULL,
  chip_ko VARCHAR(20) NULL,
  chip_en VARCHAR(30) NULL,
  image_id VARCHAR(32) NOT NULL,
  width INT NOT NULL,
  height INT NOT NULL,
  action VARCHAR(20) NULL,
  link_url VARCHAR(500) NULL,
  sort_order INT NOT NULL DEFAULT 0,
  hidden TINYINT(1) NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT fk_catalog_banners_image FOREIGN KEY (image_id) REFERENCES catalog_images(id)
);
```

## 클라이언트

- 앱: 시작 때 `GET /catalog/frames`·`/catalog/banners` 를 ETag 로 재확인하고 매니페스트를 내부 저장소에 캐시한다.
  이미지는 쓰일 때 내려받아 파일로 캐시한다. 오프라인이면 캐시 + 번들만. 모르는 프레임 id(미러링·함께 채우기)는 `GET /catalog/frames/{id}` 로 그 하나를 받는다.
- 웹(/fill): 번들 프레임은 `/frames/frames.json`, 서버 프레임은 `GET /catalog/frames` 를 합쳐 쓴다. `rot` 는 캔버스 회전으로 그린다.
- 스튜디오(/studio): 키 입력 → 프레임 올리기(창 4개를 끌어 배치·크기·회전), 목록(숨김·삭제·순서·수정), 배너 올리기·목록.

---

# 2차: 휴지통 · 포즈추천 (2026-10-22)

## 휴지통 (프레임·배너·포즈 업로드 공통)

- `DELETE /catalog/studio/{frames|banners|pose/uploads}/{id}` → 204. 지우지 않고 `deletedAt` 과 `purgeAt = deletedAt + catalog.trash-days(7)` 를 적는다.
  목록(`?all=true` 포함)에서 빠진다. `GET /catalog/frames/{id}` 는 휴지통에 있어도 200 (세션이 참조 중일 수 있다).
- `GET /catalog/studio/trash` → `{ "frames": [프레임 + deletedAt, purgeAt], "banners": […], "poseUploads": […] }`
- `POST /catalog/studio/{frames|banners|pose/uploads}/{id}/restore` → 200 항목 (deletedAt·purgeAt 지움). 휴지통에 없으면 404.
- `DELETE /catalog/studio/trash/{frames|banners|pose/uploads}/{id}` → 204 완전 삭제(이미지도). 휴지통에 없으면 404.
- 정리 스케줄(CatalogCleanupScheduler): `purgeAt` 이 지난 항목을 이미지와 함께 지운다.
- 홈 배너는 최소 한 개는 보여야 한다: 마지막으로 보이는(hidden=false, 휴지통 아님) 배너를 숨기거나(PUT hidden=true) 지우면 409 `CATALOG_LAST_BANNER`.

## 포즈추천

포즈 풀은 오프라인 파이프라인이 S3 `assets/<ver>/` 에 버전째 올린다(meta.json + kp + index.bin 등, 이미지 thumb/preview/guide/canny/cutout).
서버는 그 파일을 건드리지 않는다(index.bin 의 길이가 meta 의 샷 수와 묶여 있어 한 장만 빼도 깨진다). 대신 두 가지를 덧씌운다.

1. **덧씌우기(overrides)**: 기존 샷을 숨기거나(앱에서 안 보임) 라벨(성별·인종·계절)을 더한다.
2. **스튜디오 업로드(uploads)**: 디자이너가 올린 AI 사진. 앱은 찾아보기 목록에 섞어 보이고, 가이드로 고르면 기기에서 관절을 계산한다
   (내 사진과 같은 경로). 유사도 추천(index)에는 파이프라인이 다음 버전에 넣기 전까지 들어가지 않는다.

```
GET /catalog/pose  (공개, ETag)   ?all=true + 스튜디오 키 → 숨김 포함
{
  "updatedAt": "…",
  "overrides": [
    { "version": "v012", "shot": "ext_beach_0001", "hidden": true, "labels": { "성별": "여자", "계절": "여름" }, "updatedAt": "…" }
  ],                                  // hidden 이거나 labels 가 있는 것만. 휴지통(deletedAt)에 있는 것은 hidden=true 로 내려간다
  "uploads": [
    { "id": "pu_k2m9x1p0qz", "imageUrl": "…/catalog/images/…", "width": 1024, "height": 1365,
      "labels": { "장소": "카페", "성별": "남자", "인종": "동양", "계절": "봄·가을" },
      "sortOrder": 0, "hidden": false, "createdAt": "…", "updatedAt": "…" }
  ]                                   // 숨김·휴지통 제외 (all=true 면 숨김 포함)
}
```
- 라벨 키는 앱의 meta 라벨과 같은 한국어 토큰: `장소`(필수, 값은 거리·계단·광장·자연·공원·전시·실내·카페·해변 또는 자유 입력 ≤ 20자),
  `성별`(남자|여자), `인종`(≤ 20자 자유 입력; **앱에는 절대 보이지 않는 내부 분류**), `계절`(봄·가을|여름|겨울). 빈 값은 키를 뺀다.
- `PUT /catalog/studio/pose/overrides/{version}/{shot}` `{ "hidden"?: bool, "labels"?: {…}, "deleted"?: bool }` → 200 override.
  `deleted=true` 는 휴지통(숨김 + deletedAt/purgeAt, trash 목록 `poseOverrides` 에 나옴), 복구는 `POST /catalog/studio/pose/overrides/{version}/{shot}/restore`.
  purge 때는 S3 를 지울 수 없으므로 `hidden=true` 로 영구히 남긴다. hidden=false·labels 비어 있음·deleted 아님이면 행을 지운다.
- `POST /catalog/studio/pose/uploads` `{ "imageId", "labels": {…}, "sortOrder"?, "hidden"? }` → 201. `PUT …/{id}` 부분 갱신, `DELETE …/{id}` 휴지통.
- 검증(400 `CATALOG_INVALID_POSE`): 장소 필수, 성별·계절은 허용값, 이미지 존재.
- 앱 규칙: 어떤 분류 축(장소·성별·계절)은 **보이는 사진을 가진 값이 2개 이상**일 때만 화면에 나온다. 성별은 파이프라인 샷의 `세트`("gpt_여자·1인")에서 여자/남자를 읽고 overrides 로 덮는다. `인종` 은 어디에도 표시하지 않는다.

### DDL

```sql
ALTER TABLE catalog_frames  ADD COLUMN deleted_at DATETIME(6) NULL, ADD COLUMN purge_at DATETIME(6) NULL;
ALTER TABLE catalog_banners ADD COLUMN deleted_at DATETIME(6) NULL, ADD COLUMN purge_at DATETIME(6) NULL;
CREATE TABLE catalog_pose_overrides (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  version VARCHAR(20) NOT NULL,
  shot VARCHAR(80) NOT NULL,
  hidden TINYINT(1) NOT NULL DEFAULT 0,
  labels_json TEXT NULL,
  deleted_at DATETIME(6) NULL,
  purge_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_pose_override (version, shot)
);
CREATE TABLE catalog_pose_uploads (
  id VARCHAR(32) PRIMARY KEY,
  image_id VARCHAR(32) NOT NULL,
  width INT NOT NULL,
  height INT NOT NULL,
  labels_json TEXT NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  hidden TINYINT(1) NOT NULL DEFAULT 0,
  deleted_at DATETIME(6) NULL,
  purge_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT fk_catalog_pose_uploads_image FOREIGN KEY (image_id) REFERENCES catalog_images(id)
);
```

설정: `catalog.trash-days: 7`.

### 뒤로 미룬 것
- 업로드를 S3 `assets/studio/orig/<id>.jpg` 로도 보내 파이프라인(썸네일 Lambda 포함)이 다음 버전에 넣게 하는 것: 포즈 자산 버킷 이름과 EC2 역할의 쓰기 권한이 확인되면 붙인다.
