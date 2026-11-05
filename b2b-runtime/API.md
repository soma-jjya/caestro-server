# B2B 전용 런타임 계약

아래는 **새 서버의 계약**이다. 현재 운영 API에 반영된 경로가 아니다. FE는 새 서버와 라우팅을 검증한 프로젝트부터 전환한다.

예시 base: `https://b2b-api.peakpic.app/projects/yuja` (DNS 미생성).
공유 페이지: `https://peakpic.app/go/yuja/{code}`.

## 참여 API

| 메서드 | base 뒤의 경로 | 인증 | 응답 |
|---|---|---|---|
| GET | `/` | 없음 | 기존 B2B 공개 프로젝트 구조, LIVE + 참여 기간만 |
| POST | `/auth/guest` | 없음 | `{accessToken,refreshToken}` |
| POST | `/auth/refresh` | 없음 | body `{refreshToken}` → 새 토큰 쌍, 기존 refresh는 소모 |
| POST | `/fills` | B2B 게스트 JWT | 기존 `{frameId,frameVersion,title?}` → 201, `{slug,shareUrl,frame,fill}` |
| GET | `/fills/{code}` | B2B 게스트 JWT | `{slug,shareUrl,frame,fill}` |
| PUT | `/fills/{code}/slots/{index}` | B2B 게스트 JWT | JPEG/PNG 원본 body, `name` query 선택, 기존 `FillResponse` |
| GET | `/fills/{code}/images/{id}` | 없음 | 이미지 또는 B2B CDN으로 302 |
| GET | `/b2b/images/{id}` | 없음 | 선택 당시 프레임 사본 또는 302 |
| GET | `/catalog/images/{id}` | 없음 | 업로드 미리보기 또는 302 |

JWT: `Authorization: Bearer <token>`. 게스트 발급 시 deviceId를 재전송해 기존 신원을 되찾지 않는다. accessToken은 최대 15분, refresh는 최대 7일이며 둘 다 전체 보관 기한을 넘지 않는다. 새 발급은 새 게스트다. FE는 토큰을 API origin+slug 별로 저장하고 기존 single-flight 갱신/401 한 번 재시도를 유지한다. B2C JWT/refresh는 호환되지 않는다.

참여 페이지의 사진 선택/프레임 합성/PNG 다운로드는 기존 FE 책임이다. `imageUrl`을 그대로 사용하고 `crossOrigin='anonymous'`를 src보다 먼저 설정한다. B2B CDN은 별도 배포다. 기존 CDN의 설정을 수정하지 않는다.

프레임 공개 1~3개, 각 창 4개, 선택 시점 스냅샷과 오류 `{status,message}` 형식은 유지한다. 업로드 3MiB 상한은 Content-Length 없는 요청에도 적용한다.

게스트 발급에는 **IP당 기본 30회/60초**와 **런타임 전체 기본 300회/분**을 함께 적용한다. 둘 다 축제 전용 Redis에 집계한다. AWS에서는 실제 검토한 값을 입력한다. 신뢰된 ALB 뒤의 실제 접속 IP를 확인하며 임의 XFF 첫 값을 신뢰하지 않는다. 중앙 프록시/Cloudflare 추가 시 전체 요청 경로에서 IP 전달을 별도 검증해야 한다.

429는 `{status:429,message:"요청이 너무 잦습니다. 60초 뒤에 다시 시도해 주세요."}` 형태다. 숫자는 제한 창에 따라 달라진다. `Retry-After`는 초 단위의 보수적인 대기 시간이며 `X-RateLimit-Scope`는 `guest-ip` 또는 `guest-runtime`이다. 두 헤더를 CORS에 노출한다. FE는 기존처럼 message를 표시하고 자동 발급 재시도를 반복하지 않는다. 기존 refresh 요청은 신규 발급 IP 한도를 소비하지 않는다. 공용 Wi-Fi에서 정상 참가자가 제한되지 않는지는 행사별로 검증한다. B2C 한도와 API 계약은 바꾸지 않는다.

행사 시작 전 쓰기 409, 행사 종료 이후 쓰기 410, 보관 기한 이후 조회 410이다. PAUSED는 새 생성만 막으며 기존 네컷은 행사/보관 기간 규칙을 따른다. 새 네컷의 expiresAt은 downloadsUntil이다. 운영 중 보관 기간을 변경하려면 기존 세션 만료 시각과 정리 작업도 함께 갱신하는 별도 변경이 필요하다.

현재 독립 HTTP 표면에는 기존 B2C의 GIF/클립/프레임 변경/대기열 복구/소셜 로그인 경로를 제공하지 않는다. 엔진 내부 일부 기능이 남아 있어도 외부 경로는 열지 않았다. 슬롯 교체 시 대기열에 남기는 기존 엔진 동작은 유지한다.

## 운영자 서버 간 API

모든 요청은 `X-B2B-Admin-Key`가 필요하다. 브라우저에 키를 넣지 않는다. 현재 운영의 `STUDIO_KEY`를 자동 공유하지 않는다.

| 메서드 | 경로 | 내용 |
|---|---|---|
| GET | `/admin/runtime` | `{environment,projectId,slug,runtimeId,guestIpLimit,guestIpWindowSeconds,guestRuntimeLimitPerMinute}`; 시험 대상과 한도 확인 |
| GET | `/admin/project` | 바인딩된 프로젝트 하나 |
| PUT | `/admin/project` | 기존 기본 정보 body; 이 런타임 slug 변경 불가 |
| PUT | `/admin/project/frames` | 기존 통째 교체 계약 |
| POST | `/admin/project/publish`, `/pause`, `/resume` | 기존 상태 전환 |
| POST | `/admin/images` | 이미지 원본 body → `{id,imageUrl,width,height,contentType}` |
| GET | `/actuator/prometheus` | 운영자 키로 보호한 계측 |

프로젝트 생성은 환경 준비 시 한 번, 목록/복제/영구 삭제와 slug 라우팅은 중앙 관리 영역에서 맡는다. 이 런타임을 현재 관리자 FE의 `/b2b/admin/projects`에 바로 대입할 수는 없다. 중앙 관리자용 API/라우팅 어댑터는 다음 구현 단계다.
