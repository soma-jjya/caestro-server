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
