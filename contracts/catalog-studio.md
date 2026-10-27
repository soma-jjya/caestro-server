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

## 자산 저장소 (#186)

그림·동영상의 **바이트**는 `catalog_images` 행이 아니라 `CatalogAssetStore`(`domain/catalog/store`) 뒤에 있다. `catalog_images`(와 B2B 의
`b2b_images`)는 폭·높이·종류·올린 시각 같은 메타데이터만 들고, 바이트는 같은 id 로 저장소에 있다. 함께 채우기 `FillImageStore`(#152)와 같은 분리다.

| `catalog.asset-store` | 바이트 위치 | 그림 주소 | 어디서 |
|---|---|---|---|
| `rds` (기본) | `catalog_blobs` 테이블 | `https://api.peakpic.app/catalog/images/{id}` (서버가 중계) | 로컬·CI |
| `s3` (2단계) | S3 버킷 + CloudFront | CDN 주소 | 운영 |

- 올리기: 메타 행 저장 → 저장소 `put`. 같은 트랜잭션이라 저장소가 실패하면 행도 남지 않는다.
- 지우기(그림 교체·휴지통 비우기·고아 정리): 행 삭제 → 저장소 `delete`. 행이 먼저라 저장소 삭제가 실패해도 목록에서는 사라진 뒤다.
- B2B 사본: `copy(sourceId, b2bId)` 로 저장소 안에서 뜬다. 카탈로그 원본이 지워져도 사본은 남는다.
- RDS 구현은 `catalog_blobs` 에 없으면 옛 자리(메타 행의 `data` 컬럼)를 한 번 더 본다 — 1단계 배포 직후 아직 옮기지 않은 행용. 컬럼을 지우면 자연히 꺼진다.

### 1단계 배포 전후 SQL (ddl-auto=update 인 기존 DB)

엔티티가 `data` 컬럼을 더 이상 매핑하지 않는다. `update` 는 컬럼을 지우지 않으므로 NOT NULL 인 채 남아 새 코드의 INSERT 가 실패한다.
**배포 전에** 먼저 풀어 둔다(옛 코드는 계속 바이트를 쓰고, 새 코드는 이 컬럼 없이 INSERT 한다 — 어느 순서로 배포돼도 안전):

```sql
ALTER TABLE catalog_images MODIFY data LONGBLOB NULL;
ALTER TABLE b2b_images     MODIFY data LONGBLOB NULL;
```

배포 뒤 기존 바이트는 아래 2단계 절차로 S3 에 옮기고, 그 뒤 컬럼을 지운다.
로컬 DB 는 기존 그림이 필요 없으면 바로 `ALTER TABLE catalog_images DROP COLUMN data; ALTER TABLE b2b_images DROP COLUMN data;`.

### 2단계 운영 전환 (S3 + CloudFront)

S3 구현(`S3CatalogAssetStore`)은 객체 키 `{key-prefix}/{id}`, 공개 주소 `{cdn-base-url}/{key-prefix}/{id}`. 버킷은 비공개이고
기존 CloudFront 배포(`peakpic-pose-assets`)가 OAC 로 읽으며 경로 패턴 `/catalog/*` 가 이 버킷을 가리킨다. 객체에
`Cache-Control: public, max-age=31536000, immutable` 을 싣는다. B2B 사본은 `CopyObject` 로 S3 안에서 뜬다(서버를 거치지 않음).

- 새 응답의 `imageUrl`·`videoUrl` 은 처음부터 CDN 주소다.
- 옛 주소 `GET /catalog/images/{id}`·`GET /b2b/images/{id}` 는 CDN 주소로 **302(30일 캐시)** — 옛 응답을 들고 있는 클라이언트 호환용.
  메타 행이 없는 id 는 302 대신 404(없는 id 로 CDN 의 403 화면을 보여 주지 않는다).
- 지우기는 행 삭제 뒤 `DeleteObject`. S3 삭제가 실패하면 객체만 남고 행은 없다 — 주소를 아는 사람만 열 수 있고 다시 쓰이지 않는 id 라 해롭지 않다.

```yaml
catalog:
  asset-store: s3
  cdn-base-url: https://di519bna44kn1.cloudfront.net
  s3:
    bucket: peakpic-catalog-assets-063562646825
    key-prefix: catalog
  migrate-legacy: false
```

전환 순서(사용자가 깨진 그림을 보는 순간이 없도록 두 번 배포한다):

1. `asset-store: rds` 인 채로 `migrate-legacy: true` 와 S3 설정을 넣고 배포. 기동 직후 `CatalogAssetMigration` 이 옛 컬럼과
   `catalog_blobs` 의 바이트를 S3 로 올리고 HEAD 로 크기를 대조한 뒤에만 원본을 비운다(옛 컬럼 NULL, 블롭 행 삭제). 두 번 돌아도 안전.
   로그 `Catalog asset migration end (moved=N, failed=K)` 의 N 이 옮길 수(배포 전 `SELECT COUNT(*) … WHERE data IS NOT NULL`)와 같고
   S3 `catalog/` 객체 수가 같은지 확인.
2. `asset-store: s3`, `migrate-legacy: false` 로 바꿔 배포. 응답 주소가 CDN 으로 바뀐다.
3. 일주일 뒤 `ALTER TABLE catalog_images DROP COLUMN data; ALTER TABLE b2b_images DROP COLUMN data;`. `catalog_blobs` 는 운영에서 비어 있다.

## 설정

```yaml
catalog:
  studio-key: ""            # 비면 스튜디오(쓰기) 전체 403. 운영 값은 Secrets Manager(peakpic/prod/app), 기동 때 읽는다
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
  `성별`(남자|여자), `인종`(동양|서양; **앱에는 절대 보이지 않는 내부 분류**), `계절`(봄·가을|여름|겨울). 빈 값은 키를 뺀다.
- `PUT /catalog/studio/pose/overrides/{version}/{shot}` `{ "hidden"?: bool, "labels"?: {…}, "deleted"?: bool }` → 200 override.
  `deleted=true` 는 휴지통(숨김 + deletedAt/purgeAt, trash 목록 `poseOverrides` 에 나옴), 복구는 `POST /catalog/studio/pose/overrides/{version}/{shot}/restore`.
  purge 때는 S3 를 지울 수 없으므로 `hidden=true` 로 영구히 남긴다. hidden=false·labels 비어 있음·deleted 아님이면 행을 지운다.
- `POST /catalog/studio/pose/uploads` `{ "imageId", "labels": {…}, "sortOrder"?, "hidden"? }` → 201. `PUT …/{id}` 부분 갱신, `DELETE …/{id}` 휴지통.
- 검증(400 `CATALOG_INVALID_POSE`): 장소 필수, 성별·계절·인종은 허용값, 이미지 존재.
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

---

# 3차: 홈 배너 동영상 (2026-10-22)

배너는 사진(필수, 동영상의 대체·첫 화면)에 더해 동영상을 하나 가질 수 있다. 앱은 동영상 배너를 소리 없이 한 번 재생하고,
**영상 길이만큼 보여 준 뒤** 다음 배너로 넘긴다(사진 배너는 5초).

## 업로드

```
POST /catalog/studio/videos?width=1080&height=1350&durationMs=8200   (X-Studio-Key)
Content-Type: video/mp4, raw body ≤ catalog.max-video-bytes (기본 15728640)
→ 201 {"videoId":"…22자…","url":"https://api.peakpic.app/catalog/images/{videoId}","width":1080,"height":1350,"durationMs":8200,"contentType":"video/mp4"}
```
- 검증: Content-Type 이 video/mp4, 본문 4..8 바이트가 `ftyp`(MP4), 크기 초과 413 `CATALOG_VIDEO_TOO_LARGE`,
  width·height 1..4096, durationMs 500..60000 이 아니면 400 `CATALOG_INVALID_VIDEO`. 폭·높이·길이는 브라우저가 읽어 보낸다.
- 저장: `catalog_images` 에 같이(content_type video/mp4), 새 열 `duration_ms`.
- `GET /catalog/images/{id}` 는 동영상이면 Range 요청을 받는다(206, `Accept-Ranges: bytes`). 캐시 헤더는 사진과 같다.

## 배너

- 요청(POST/PUT) 에 `videoId`(문자열, 업로드한 동영상) 와 `clearVideo`(true 면 동영상을 뗀다) 추가. PUT 에서 둘 다 없으면 그대로.
  다른 동영상으로 바꾸거나 떼면 이전 동영상 바이트는 지운다. 휴지통 정리·즉시 삭제 때도 같이 지운다.
- 응답에 `videoUrl`(없으면 null), `videoDurationMs`(없으면 null) 추가.
- 고아 정리: 배너의 video_id 가 가리키는 행은 고아가 아니다.
- 검증: videoId 가 있으면 content_type 이 video/mp4 인 catalog_images 행이어야 한다(아니면 400 `CATALOG_INVALID_BANNER`).

## DDL

```sql
ALTER TABLE catalog_images  ADD COLUMN duration_ms INT NULL;
ALTER TABLE catalog_banners ADD COLUMN video_id VARCHAR(32) NULL,
  ADD CONSTRAINT fk_catalog_banners_video FOREIGN KEY (video_id) REFERENCES catalog_images(id);
```

설정: `catalog.max-video-bytes: 15728640`.

## 클라이언트

- 스튜디오: 배너 편집기에서 사진 또는 동영상(MP4). 동영상을 고르면 첫 장면을 잘라 JPEG 로 올려 imageId(대체 사진)로, 동영상은 videoId 로.
  미리보기·폰 미리보기에서 소리 없이 재생.
- 앱: 동영상을 내려받아 캐시한 뒤 소리 없이 한 번 재생, 끝나면(또는 videoDurationMs 가 지나면) 다음 배너. 받기 전·실패 시에는 사진.

# 4차: AI 네컷 프레임 Pro·무료 · 제목 배지 (2026-10-23)

Peakpic Pro 구독(contracts/subscription-google-play.md)과 함께, 일부 AI 네컷 프레임은 Pro 구독자만 쓴다.
무료 사용자에게 Pro 프레임은 앱에서 어둡게 덮이고 흰 자물쇠가 보이며, 누르면 결제 화면이 열린다(잠금은 앱이 판단).
앱 번들 프레임은 갸루 야호·코지 패치워크만 무료(앱에 고정), 기본 색(검정·흰색)과 커스텀은 늘 무료. 스튜디오 프레임은 아래 `pro` 값을 따른다.

## 프레임

- 요청(`POST`·`PUT /catalog/studio/frames`)에 추가:
  - `pro` (boolean, 선택): Pro 전용인가. 생성에서 생략하면 **true**. 수정은 보낸 때만 바뀐다.
  - `badge` ({ko, en}, 선택): 제목 왼쪽 작은 상자 문구('이벤트'·'인기'). 한국어 8자, 영어 12자까지(넘으면 400 `CATALOG_INVALID_FRAME`).
    수정에서 **한국어를 비워 보내면 배지를 지운다**. 보내지 않으면 그대로.
- 응답(공개 `GET /catalog/frames` 와 스튜디오 목록 공통)에 추가: `pro` (boolean), `badge` ({ko, en} 또는 null, en 이 비면 ko 복사).
- 4차 이전에 올린 프레임은 `pro` 열이 null 이고 **Pro 로 내려간다**(무료로 풀려면 스튜디오에서 바꾼다).

## DDL

```sql
ALTER TABLE catalog_frames ADD COLUMN pro BIT(1) NULL,
  ADD COLUMN badge_ko VARCHAR(20) NULL,
  ADD COLUMN badge_en VARCHAR(40) NULL;
```

## 클라이언트

- 스튜디오: 프레임 편집기에 '이용' Pro | 무료(새 프레임은 Pro)와 '배지' 입력란(한국어·English, 선택). 목록 카드에 PRO / 무료 표시,
  선반·폰 미리보기는 무료 사용자의 앱 그대로(잠금·배지, 무료 프레임이 앞). 서버가 `pro` 를 내려주지 않으면(4차 전 서버) 토글을 막아 둔다.
- 앱: `pro` 가 없으면 Pro 로 본다. 무료 사용자에게는 풀린 프레임이 잠긴 프레임보다 앞에 온다. 배지는 제목 왼쪽 빨간 상자, 없으면 안 보인다.

# 5차: 기본 프레임 덮어쓰기 (2026-10-23)

앱에 든 기본 프레임 9개(단색 `Black`·`White`, AI `gyaru`·`street`·`chrome`·`birthday`·`picnic`·`patchwork`·`vintage`)를
스튜디오에서 고친다. 프레임 id 는 그대로라 이미 만든 네컷·함께 채우기 세션과 그대로 이어진다. 덮어쓰지 않은 값은 앱에 든 그대로.

## 덮어쓸 수 있는 것

| | 단색(검정·흰색) | AI 프레임 |
|---|---|---|
| 이름·한 줄 소개(ko/en) | ✓ | ✓ |
| Pro / 무료 | ✓ | ✓ (기본: 갸루 야호·코지 패치워크 무료, 나머지 Pro) |
| 배지 | ✓ | ✓ |
| 숨김 | ✓ | ✓ |
| 그림·창 | ✗ (그림이 없다) | ✓ (새 그림은 창 4개와 함께) |
| 삭제(휴지통) · 되살리기 · 원래대로 | ✓ | ✓ |

## API (스튜디오, X-Studio-Key)

- `PUT /catalog/studio/builtins/{id}` `{title?, subtitle?, pro?, badge?, hidden?, imageId?, windows?, resetArtwork?}` → 200 덮어쓴 값
  - 보낸 필드만 바뀐다. 이름·소개·배지는 **한국어를 비워 보내면** 앱에 든 값으로(배지는 없음)
  - `imageId` 는 `windows`(4개)와 함께. `windows` 만 보내면 이미 바꾼 그림의 창만 고친다. `resetArtwork: true` 는 앱에 든 그림·창으로
  - 단색에 그림·창, 창 4개가 아님 → 400 `CATALOG_INVALID_FRAME`. 기본 프레임 id 가 아님 → 404 `CATALOG_BUILTIN_NOT_FOUND`
- `DELETE /catalog/studio/builtins/{id}` → 204. 앱 목록에서 빠지고 휴지통에 남는다(기본 프레임은 정리 배치가 지우지 않음, 언제든 되살림)
- `POST /catalog/studio/builtins/{id}/restore` → 200. 휴지통에 없으면 404 `CATALOG_NOT_IN_TRASH`
- `POST /catalog/studio/builtins/{id}/reset` → 204. 덮어쓴 값을 모두 지워 앱에 든 그대로(휴지통에 있었으면 되살아남)
- `GET /catalog/studio/trash` 에 `builtins` (휴지통의 기본 프레임) 추가

## 응답

`GET /catalog/frames` 에 `builtins` 추가(공개·스튜디오 공통, 덮어쓴 것만, **숨김·휴지통 포함** — 앱이 목록에서 빼야 하므로):

```
{ "id": "gyaru", "kind": "art",            // base | art
  "title": {"ko","en"} | null, "subtitle": {...} | null,   // null = 앱에 든 값
  "pro": true | false | null,               // null = 앱 기본값
  "badge": {...} | null,
  "hidden": false,                          // 숨김이거나 휴지통이면 true
  "deletedAt": null,                        // 휴지통에 넣은 시각
  "imageUrl": null, "width": null, "height": null, "windows": null,   // 바꾼 그림이 있을 때만
  "updatedAt": "…" }
```

목록의 `updatedAt`·ETag 는 덮어쓴 값의 수정에도 바뀐다.

## DDL

```sql
CREATE TABLE catalog_builtin_frames (
  id           VARCHAR(32)  NOT NULL PRIMARY KEY,
  title_ko     VARCHAR(40)  NULL, title_en VARCHAR(60) NULL,
  subtitle_ko  VARCHAR(80)  NULL, subtitle_en VARCHAR(120) NULL,
  pro          BIT(1)       NULL,
  badge_ko     VARCHAR(20)  NULL, badge_en VARCHAR(40) NULL,
  hidden       BIT(1)       NOT NULL,
  image_id     VARCHAR(32)  NULL,
  width        INT NULL, height INT NULL,
  windows_json TEXT NULL,
  deleted_at   DATETIME(6)  NULL,
  updated_at   DATETIME(6)  NOT NULL,
  CONSTRAINT fk_catalog_builtin_frames_image FOREIGN KEY (image_id) REFERENCES catalog_images(id)
);
```

고아 그림 정리는 덮어쓴 기본 프레임이 가리키는 그림을 지우지 않는다.

## 클라이언트

- 앱: 기본 프레임에 이름·소개·Pro·배지를 덮어씌우고, 숨김·휴지통이면 목록(홈 선반·프레임 목록·네컷 시트·칩)에서 뺀다.
  그림이 바뀐 AI 프레임은 서버 그림·창으로 그린다. 이미 그 프레임으로 만든 네컷은 그대로 열린다.
- 스튜디오: '앱 기본' 카드에도 수정·숨기기·삭제·원래대로. 단색은 이름·소개·Pro·배지·숨김만. 서버가 `builtins` 를 모르면 버튼을 막는다.
