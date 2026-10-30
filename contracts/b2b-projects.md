# B2B 프로젝트·프레임·함께 채우기 (#174)

아래는 B2B API의 연동 계약이다. 기본 구현은 PR #191로 병합됐다. 2026-10-28 후속 변경은 개인 공유 주소의 `/go` 경로와 CDN 이미지의 캔버스 사용 설정이며, 운영 반영 여부는 별도로 검증한다.

운영 API 기준 주소: `https://api.peakpic.app`. FE는 기준 주소를 환경 변수로 분리하고, 배포된 환경의 `/v3/api-docs`에서 B2B 경로가 반영됐는지 확인한 뒤 실제 연결한다.

## 경로와 책임

| 구분 | 웹 페이지 (FE) | 백엔드 API |
|---|---|---|
| 운영자 | `https://peakpic.app/admin` | `/b2b/admin/projects/**` |
| 축제·매장 입장 | `https://peakpic.app/admin/go/{slug}` | `GET /b2b/projects/{slug}` |
| 개인 네컷 | `https://peakpic.app/go/{slug}/{code}` | `POST /b2b/projects/{slug}/fills`, `GET /b2b/projects/{slug}/fills/{code}` |

운영자/입장 경로는 FE에서 관리한다. 서버는 `${b2b.web-base-url}/{slug}/{code}`로 `shareUrl`을 만들며, 기본값은 `https://peakpic.app/go`다. 운영 환경에서 `B2B_WEB_BASE_URL` 또는 `b2b.web-base-url`을 따로 지정했다면 같은 값으로 수정하고 서버 인스턴스를 교체해야 한다. 생성과 조회 응답 모두 현재 설정으로 URL을 만들며 DB에 저장된 code나 프레임 스냅샷은 바꾸지 않는다.

서버는 API와 공유 URL을 제공한다. FE의 동적 라우트/페이지 자체를 생성하거나 배포하지 않는다.
QR에는 입장 URL만 넣는다. 사용자 계정의 B2B 링크 목록은 없으므로 사용자가 개인 URL을 보관·공유한다.

`domain/b2b`가 프로젝트, 선택 프레임, 이미지 사본, 세션 소속/스냅샷을 담당한다.
기존 `fill`의 코드 발급, 4개 슬롯, 사진/클립/GIF 저장소, 대기열, 만료, 쓰기 한도를 재사용한다.
B2C `/catalog/frames`에 B2B 프레임을 등록하지 않는다. B2C 응답 구조도 유지한다.

## 인증

- 관리자 API: 기존 `X-Studio-Key` (`catalog.studio-key`) 또는 기존 ADMIN JWT. 새 운영자 로그인/JWT 발급 시스템은 없다.
- 관리자 FE의 서버 실행 환경에서만 키를 보낸다. 배포 인프라는 Cloudflare/AWS이며, 실제 FE 서버가 실행되는 서비스의 서버 전용 시크릿 `STUDIO_KEY`에 저장한다. 브라우저 번들/응답에 넣지 않는다. 구체적인 FE 서비스와 배포 방식은 연결 대상 확인 후 확정한다.
- 관리자 FE의 비밀번호 게이트/세션 검사는 화면뿐 아니라 관리자 서버 프록시에도 적용한다. 실제 키 값은 별도로 설정한다.
- 이 키의 인증 범위는 기존 `/catalog/**`와 신규 `/b2b/admin/**`다. `/b2b/projects/**` 참여 요청이나 `/fills/**`의 사용자 인증을 대신하지 못한다.
- 공개 프로젝트/프레임 이미지 GET은 인증 불필요하다.
- B2B 네컷 생성/조회 및 기존 사진 쓰기는 `/auth/guest`로 받은 기존 게스트 JWT 또는 회원 JWT가 필요하다. 게스트 발급 계약은 기존 인증 API를 따른다.
- 생성 한도와 활성 세션 상한은 B2C/B2B가 함께 사용한다. 기본값은 사용자당 10회/시간, 활성 20개. 사진 업로드도 기존 제한을 따른다.

게스트 인증은 `POST /auth/guest`, JSON 본문 `{"deviceId":"클라이언트가 생성하고 보관하는 식별자"}`이고 응답은 `{"accessToken":"...","refreshToken":"..."}`이다. 같은 브라우저에서는 기존 식별자/인증 흐름을 재사용한다. 참여 요청에는 `Authorization: Bearer {accessToken}`을 보낸다.

## 프로젝트

`id`, `name`(1..60), `slug`, `description`(0..120), `status`, `frames`, `publishedAt`, `createdAt`, `updatedAt`.
시각은 UTC ISO 8601이다. description 누락/null은 빈 문자열로 저장한다.

slug는 `^[a-z0-9-]{3,40}$`, 전체 유일하며 입력을 자동 소문자화하지 않는다.
예약어: `b2b, fill, admin, auth, api, login, assets, static, frames, catalog, d, www, app, health, studio`.
`studio`는 이미 운영 중인 페이지와의 충돌을 막기 위해 포함한다. 새로운 FE 고정 경로도 이 목록과 함께 관리한다.

| 메서드·경로 (기준 `/b2b/admin/projects`) | 요청/응답 |
|---|---|
| `GET /` | 프로젝트 배열. updatedAt 내림차순, 동률이면 id 오름차순. frames 포함 |
| `GET /{id}` | 프로젝트 단건 |
| `DELETE /{id}` | DRAFT/PAUSED 영구 삭제 → 204 (본문 없음). LIVE 409, 없는 id 404 |
| `POST /` | `{name,slug,description}` → 201. DRAFT, frames=[] |
| `PUT /{id}` | `{name,slug,description}` → 200. 전체 기본 정보 교체 |
| `PUT /{id}/frames` | 아래 전체 교체 계약 → 200 |
| `POST /{id}/publish` | DRAFT → LIVE, 본문 없음 |
| `POST /{id}/pause` | LIVE → PAUSED, 본문 없음 |
| `POST /{id}/resume` | PAUSED → LIVE, 본문 없음 |
| `POST /{id}/duplicate` | `{name,slug}` → 201. 새 id/프레임 id, DRAFT, publishedAt=null |
| `GET /slug-check?slug=yuja&exclude=bp_...` | `{available:true}` 또는 `{available:false,reason:"format"|"reserved"|"duplicate"}` |

위 표의 `/`는 기준 경로 자체를 뜻한다. 중복 확인은 안내이며 저장 시에도 DB 유일 제약으로 검사한다.
최초 공개 이후 slug 변경은 PAUSED에서도 409다. 이름/설명은 수정할 수 있다.
publish/resume 시 프레임이 **1~3개**여야 하며, **모든 프레임에 이미지와 창 4개**가 있어야 한다.
LIVE에서 프레임을 저장할 때도 같은 조건을 유지해야 한다. 0개이거나 미완성 프레임이 하나라도 섞여 있으면 기존 `B2B_NOT_PUBLISHABLE`(409, `{status,message}`)로 거부한다. 유효한 항목만 골라 공개하지 않는다.
DRAFT/PAUSED에서는 0..3개 및 미완성 설정을 저장할 수 있다. 상태와 무관하게 3개 초과 저장은 기존처럼 400이다.
공개 프로젝트 조회는 저장된 1~3개를 displayOrder 순으로 그대로 반환한다. 네컷 생성은 해당 프로젝트에 현재 속한 frameId와 frameVersion으로 선택하며, 개수가 1~2개여도 동일하다.
상태에 맞지 않는 publish/pause/resume은 409다. publishedAt은 최초 공개 시각을 유지한다.
복제는 설정과 불변 이미지 사본만 공유한다. 세션·참여자·사진을 복사하지 않는다.

### 프로젝트 삭제

`DELETE /b2b/admin/projects/{id}`는 기존 관리자 인증을 사용하며 DRAFT/PAUSED만 허용한다. LIVE는 먼저 pause해야 하며 서버가 자동 중지하지 않는다. 성공 응답은 204로 본문이 없으므로 FE에서 JSON 파싱을 시도하지 않는다. 없는 id(이미 삭제한 id 포함)는 404, LIVE는 409이며 오류는 기존 `{status,message}`다.

삭제하면 프로젝트·현재 프레임·B2B 네컷 연결·네컷 세션·슬롯·대기열·DB 사진이 한 트랜잭션으로 제거된다. 기존 참여/공유 링크도 사용할 수 없으므로 FE의 삭제 확인창에 이를 명시한다. 단순 pause와 달리 기존 네컷을 유지하지 않는다. 다른 프로젝트의 현재 프레임 또는 네컷 스냅샷이 참조하는 이미지 사본과 카탈로그 원본 업로드는 보존한다.

외부 저장소는 DB 트랜잭션으로 롤백할 수 없으므로, 정리 대상을 `b2b_asset_deletions`에 함께 기록한 뒤 커밋 직후 삭제를 시도한다. 실패하면 작업을 보존하고 5분 뒤 재시도한다(배치 기본 60초 주기, `cleanup.b2b.asset-delete-delay-ms`). 204는 DB 삭제 및 저장소 정리 예약의 완료를 뜻하며 저장소 장애 때 물리 삭제는 늦어질 수 있다. 이미 캐시되거나 다운로드된 공개 이미지를 회수하는 기능은 포함하지 않는다.

배포 전에 [삭제 작업 테이블 SQL](sql/174-b2b-project-delete.sql)을 적용한다. 운영의 지정 점검 프로젝트 11개 정리 절차는 [운영 정리 안내](../infra/b2b/README.md)를 따른다.

## 프레임과 이미지

먼저 기존 `POST /catalog/studio/images`에 PNG/JPEG 바이트를 업로드한다(기존 최대 8MB).
`Content-Type: image/png` 또는 `image/jpeg`와 이미지 원본 바이트를 요청 본문으로 보내며, multipart/FormData 형식이 아니다. 응답은 `{imageId,url,width,height,contentType}`다. 프레임/배너 등록 API는 호출하지 않는다.
응답의 `imageId`를 아래 요청에 넣는다. 업로드만 하고 저장하지 않은 원본은 기존 카탈로그 정리 정책을 따른다.

```json
{
  "frames": [
    {
      "id": "bf_수정할때만포함",
      "name": "유자 프레임 1",
      "layout": "STRIP",
      "imageId": "카탈로그업로드imageId",
      "windows": [
        {"x": 0.1, "y": 0.05, "w": 0.8, "h": 0.2, "rot": 0},
        {"x": 0.1, "y": 0.28, "w": 0.8, "h": 0.2, "rot": 0},
        {"x": 0.1, "y": 0.51, "w": 0.8, "h": 0.2, "rot": 0},
        {"x": 0.1, "y": 0.74, "w": 0.8, "h": 0.2, "rot": 0}
      ]
    }
  ]
}
```

- 배열 전체를 한 트랜잭션으로 교체한다. 배열 순서가 0부터 시작하는 displayOrder다.
- id 생략/null은 신규 생성. 기존 id는 같은 프로젝트의 현재 프레임이어야 하며 중복 id는 거부한다. 빠진 id는 현재 선택 목록에서 삭제한다.
- name은 1..30자, layout은 `STRIP`(1×4) 또는 `GRID`(2×2). 카탈로그의 전역 sortOrder와 무관하다.
- 초안에서 imageId=null은 이미지 없음, windows=null/[]는 미완성 상태다. 창은 최대 4개이며 있는 창은 각각 유효해야 한다.
- 창 좌표는 카탈로그와 같다. `x,y`는 회전 전 사각형 좌상단, `w,h`는 이미지 폭/높이에 대한 비율, `rot`는 중심 기준 시계 방향 도(-180..180). 슬롯 번호는 배열 순서 0..3이다. 유한수, 양의 크기, 이미지 안의 경계를 검사한다(카탈로그와 같은 0.001 허용 오차).
- 이름/레이아웃/이미지/창/순서가 바뀌면 version +1. 동일 요청 재전송은 버전을 유지한다. 신규/복제 프레임은 version=1이다.
- 응답에는 `imageUrl`, `width`, `height`, `displayOrder`, `version`이 추가된다. `catalogFrameId`는 없다.

**FE는 응답의 imageUrl을 그대로 사용해야 한다.** imageId는 원래 카탈로그 업로드 id이고, imageUrl은 별도 보관된 `/b2b/images/{id}` 사본을 가리킨다.
저장 시 PNG/JPEG 바이트를 B2B 테이블로 복사하므로 카탈로그 원본이 교체/삭제/정리되어도 B2B는 유지된다.
기존 프레임을 같은 imageId로 다시 저장하거나 프로젝트를 복제하면 보관된 사본을 재사용한다.
삭제된 프레임을 신규로 다시 만들면서 원본도 이미 정리된 경우에는 이미지를 다시 업로드해야 한다.

## 참여·공유·다운로드

1. QR/입장 URL에서 `GET /b2b/projects/{slug}`를 호출한다. LIVE만 `{name,slug,description,frames}`를 반환하고 DRAFT/PAUSED/없는 slug는 모두 404다.
2. 선택한 프레임의 id/version을 보관하고 기존 게스트 인증을 받는다.
3. `POST /b2b/projects/{slug}/fills`에 Bearer JWT와 `{frameId,frameVersion,title?}`를 보낸다.
4. 201 응답의 shareUrl로 이동하고 사진을 기존 `PUT /fills/{code}/slots/{index}`에 올린다. 개인 링크를 공유한다.
5. 참여자는 기존 게스트 JWT로 `GET /b2b/projects/{slug}/fills/{code}`를 조회하고 같은 슬롯 API로 사진을 넣는다.
6. FE는 슬롯 4개가 채워지면 응답의 고정 frame 설정/이미지와 슬롯 사진으로 합성·다운로드한다. 서버에서 완성 PNG를 합성하는 신규 API는 없다.

사진 업로드는 `PUT /fills/{code}/slots/{index}?name=표시이름`이며 index는 0..3, name은 선택이다. PNG/JPEG 원본 바이트를 Content-Type과 함께 전송한다(multipart 아님, 기본 최대 3MB). 기존 업로드 API는 Content-Length가 필요하다. 브라우저에서는 File/Blob의 고정 길이 본문을 사용하고, 서버 프록시는 바이트 길이를 보존한다. 길이가 없으면 411, 크기 초과는 413이다. 슬롯의 `imageUrl` 4개가 모두 채워진 상태를 완료로 판단한다.

생성/조회 응답 (`shareUrl`은 기본 `b2b.web-base-url` 기준 예시):

```json
{
  "slug": "yuja",
  "shareUrl": "https://peakpic.app/go/yuja/K7X2MQ",
  "frame": {"id":"bf_...", "name":"유자 1", "layout":"STRIP", "imageId":"...", "imageUrl":"https://api.peakpic.app/b2b/images/bi_...", "width":400, "height":1200, "windows":[], "displayOrder":0, "version":1},
  "fill": {"code":"K7X2MQ", "ownerMe":true, "frame":"White", "layout":"Strip", "artFrameId":null, "customFrame":null, "title":"우리 네컷", "borderless":false, "slots":[], "gifUrl":null, "expiresAt":"2026-10-29T12:00:00Z", "createdAt":"2026-10-22T12:00:00Z", "queue":[]}
}
```

위 예제의 windows/slots는 지면상 생략했으며 실제 생성 응답에는 각각 4개가 있다.
외곽 `frame`이 B2B 렌더링 기준이다. 내부 `fill.frame/layout`은 기존 엔진의 기본값/배치이고 B2B 프레임 이미지를 대체하지 않는다.
슬롯 쓰기 응답은 기존 FillResponse 그대로이므로 FE는 외곽 스냅샷을 유지하고 `fill` 상태만 갱신하거나 B2B GET으로 다시 읽는다.

선택한 뒤 프레임이 수정되면 생성 시 409(`B2B_FRAME_VERSION_CHANGED`)이므로 목록을 다시 읽고 재선택한다.
다른 프로젝트의 프레임은 404, 다른 slug의 code도 404다. 코드 대소문자는 정규화한다.
PAUSED는 입장/신규 생성을 막지만 기존 세션의 조회·사진 채우기·다운로드는 만료까지 유지한다.
따라서 개인 네컷 페이지를 열 때 공개 프로젝트 GET의 성공을 선행 조건으로 두지 않는다. B2B 네컷 GET을 직접 호출해 저장된 프레임과 fill 상태를 읽는다. 다른 참여자의 변경은 해당 GET을 재조회해 반영한다.
보관 기간은 기존 `fill.ttl-days`(기본 7일)를 따른다. 만료 조회는 410, 삭제/정리 후에는 404다.

B2B 세션은 생성 시 frameLocked=true다. 기존 `PUT /fills/{code}/frame` 및 `/frame-assets/{k}`로 우회 변경해도 409다.
기존 B2C 세션은 false를 기본값으로 유지하고 프레임 변경을 계속 지원한다.
사진/대기열/클립/GIF/소유자 삭제는 기존 [fill 계약](fill-together.md)을 따른다.

## 데이터 수명과 배포

- 신규 테이블: `b2b_projects`, `b2b_frames`, `b2b_images`, `b2b_fills`.
- `b2b_images` 는 메타데이터만 든다(#186). 바이트는 카탈로그와 같은 `CatalogAssetStore` 에 같은 id 로 있고, 사본은 저장소 안에서 `copy` 로 뜬다 — contracts/catalog-studio.md "자산 저장소".
- 기존 테이블 추가 컬럼: `fill_sessions.frame_locked NOT NULL DEFAULT false`.
- `b2b_fills`는 기존 fill session을 참조하며 **ON DELETE CASCADE**가 필수다. 기존 소유자 삭제/만료 배치가 세션을 지우면 B2B 연결도 함께 정리한다.
- 참여 당시 프레임 응답 JSON과 이미지 사본을 b2b_fills에 고정한다. 현재 선택 프레임을 수정/삭제해도 기존 세션에는 적용하지 않는다.
- B2B 이미지 정리 배치는 현재 프레임과 세션 스냅샷 양쪽에서 참조하지 않는 사본만 삭제한다. 생성 후 1일 유예, `cleanup.b2b.cron` 기본 매일 05:00(서버 시간대), ShedLock 적용.
- 이미지 URL 호스트는 기존 `catalog.public-base-url`을 사용한다. 웹 공유 URL은 `b2b.web-base-url`, 기본 `https://peakpic.app/go`다.
- S3 모드의 이미지 GET은 CloudFront로 리다이렉트된다. CDN의 `/catalog/*` 응답은 요청의 Origin 유무와 무관하게 `Access-Control-Allow-Origin: *`를 포함해야 한다. [공유 URL·CloudFront 운영 적용 절차](../infra/cloudfront/README.md)를 따른다.
- 추가 DB 변경은 [검토용 SQL](sql/174-b2b.sql)을 확인한다. 스키마/외래키 반영 → 백엔드 → FE 순서로 배포한다. 이 작업에서 운영 DB에 SQL을 실행하지 않는다.

에러 응답은 기존 `{"status":409,"message":"..."}` 형식이다. 에러 코드 이름은 서버 코드 식별자이며 응답에 새 code 필드를 추가하지 않는다.
프레임 버전 충돌 등은 요청 종류와 HTTP 상태를 기준으로 처리한다. 요청 검증 실패는 400, 권한 없음은 403, 없는/공개되지 않은 프로젝트는 404, 상태·버전 충돌은 409, 만료는 410, 업로드 길이 누락은 411, 크기 초과는 413, 사용량 제한은 429다. 인증 만료 처리는 기존 JWT 갱신 흐름을 따른다.

## 검증 항목

2026-10-24: `develop`의 `8d605a7`을 반영했다. 기존 B2B 코드와 구독/카탈로그 변경은 충돌 없이 통합됐고 `git diff --check`가 통과했다. 통합 후 전체 빌드 재검증은 아직 완료되지 않았다. 에이전트 실행 환경에서는 Docker 파이프 및 Gradle 캐시 접근 권한이 거부되어 일반 PowerShell에서 재검증해야 한다.

아래 결과는 **통합 전** 기준점의 검증이다. 2026-10-22 23:02 KST, `develop`의 `c7a8a55`에 연결한 작업 코드에서 아래 전체 빌드를 실행해 **BUILD SUCCESSFUL, 348개 모두 통과(실패/오류/건너뜀 0)**를 확인했다. Java 21, 프로젝트에 선언된 의존성, ci 프로파일, 별도 MySQL 8.0/Redis 7.0 컨테이너를 사용했으며 테스트 필터/제외는 없다. 전체 소스/테스트 소스 컴파일과 실행 JAR 생성도 완료했다.

같은 날 23:22에 시작한 추가 실행은 `AppleOAuthProviderTest` 4개와 `TargetLifecycleWatcherTest` 1개가 `java.net.SocketException: Bad address: listen`으로 실패했다. 이전 성공 기록만으로 현재 브랜치의 전체 검증 완료를 판단하지 않는다.

```powershell
.\gradlew.bat build --offline --no-daemon --console=plain --rerun-tasks
```

이 PC에서 IPv4 임의 포트의 `Bad address: listen` 오류가 간헐적으로 재현되어 검증 실행에만 `SERVER_ADDRESS=::1`을 사용했다. 애플리케이션 설정 파일은 변경하지 않았고, 실행 후 환경 변수를 복원했다. 소스/빌드 설정의 실행 전후 SHA-256이 일치하며 임시 컨테이너도 모두 정리했다.

종료 시점에 Hibernate의 외래키 삭제(`HHH000478`) 로그가 남았다. 여러 Spring 컨텍스트가 같은 테스트 DB를 create-drop으로 정리하는 단계에서 발생했으며, 테스트 실패나 빌드 실패로 이어지지 않았다. 운영 스키마 반영은 이 검증에 포함되지 않는다.

- `B2bPersistenceTest`: 상태 전이, slug 고정/동시 중복, 전체 교체 롤백, 소속/버전 검사, 복제, 이미지 원본 삭제 후 보존, 기존 fill 삭제 시 FK 정리.
- `B2bSecurityWebTest`: 공개/관리자/참여 인증 구분, 키 범위, 요청 검증, 기존 생성 한도 호출.
- `B2bPolicyTest`: 예약어/slug 형식, 좌표의 유한수·범위 검증.
- 기존 catalog/fill/security 회귀, 실제 Redis 스크립트/레이트리미터, MySQL 기반 FillSlotRaceTest/SessionRecordOrderingTest/FillCleanupIsolationTest, ServerApplicationTests도 모두 통과했다. B2B 전용 저장 테스트는 H2에서 실행한다.
