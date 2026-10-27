# B2B 공유 주소와 CDN 이미지 CORS

2026-10-28 요청의 적용 절차다. 파일을 커밋하는 것만으로 AWS 설정은 바뀌지 않는다. 콘솔에서 `/catalog/*`의 응답 헤더 정책이 Managed-SimpleCORS임을 확인했다. 함수 연결 상태와 적용 결과는 아직 확인하지 않았다.

정정: 앞서 제공한 CustomHeadersConfig의 `Access-Control-Allow-Origin` 설정은 AWS에서 거부된다. 콘솔의 정책 생성이 실패했으며 기존 배포에는 적용되지 않았다. AWS CLI `--generate-cli-skeleton output` 검증은 로컬 입력 형식만 확인하므로 서비스의 실제 생성 가능 여부를 검증한 것이 아니다. 잘못된 JSON을 CloudFront Function 코드로 대체한다.

## 1. 공유 주소

운영 서버의 유효 설정을 다음으로 맞춘다.

```yaml
b2b:
  web-base-url: https://peakpic.app/go
```

환경 변수 이름은 `B2B_WEB_BASE_URL`이다. 저장소의 application.yml과 서비스 기본값은 위 주소를 사용한다. 운영 환경 변수, application-prod.yml 또는 Secrets Manager(`peakpic/prod/app`)에서 과거 값을 지정했다면 해당 항목도 수정한다. 다른 시크릿은 유지한다. 기동 시 읽는 값이므로 설정 반영 후 인스턴스를 교체하고 실제 완료를 확인한다.

응답 예시: `https://peakpic.app/go/yuja/K7X2MQ`. 생성 POST와 기존 세션 GET 모두 현재 설정으로 shareUrl을 만든다. DB 마이그레이션은 필요 없다. 이미 배포된 예전 주소의 리다이렉트 처리는 FE 라우팅에서 관리한다.

## 2. CloudFront Function으로 응답 헤더 추가

- 배포 도메인: `di519bna44kn1.cloudfront.net`.
- 현재 코드의 대상 객체 경로: `/catalog/*`. B2B 프레임 사본도 CatalogAssetStore의 같은 경로를 사용한다. 운영에서 key-prefix를 바꿨다면 실제 이미지 경로와 매칭되는 behavior를 확인한다.
- 추가할 **응답** 헤더: `Access-Control-Allow-Origin: *`.
- 위치: CloudFront Function의 **Viewer response(뷰어 응답)**. 기존 Managed-SimpleCORS 응답 헤더 정책은 유지한다.

[AWS 공식 예제](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/example_cloudfront_functions_add_cors_header_section.html)는 viewer response 함수에서 CORS 헤더를 추가한다. 여기서는 공개 이미지의 익명 캔버스 사용을 위해 요청의 Origin을 검사하지 않고 `*`를 지정한다. 코드: [public-image-cors.js](public-image-cors.js).

1. 위 배포의 Behaviors → `/catalog/*` → Edit에서 기존 설정을 기록한다. 특히 **Function associations 전체**를 먼저 확인한다. Managed-SimpleCORS와 기본값(`*`) behavior는 유지한다.
2. 기존 viewer response 함수가 있으면 교체하지 않는다. 함수 원본과 사용 중인 다른 behavior를 확인한 뒤 사본에 필요한 로직을 병합하는 별도 검토가 필요하다. Viewer request 또는 viewer response에 Lambda@Edge가 있으면 CloudFront Function을 함께 연결할 수 없으므로 기존 Lambda 로직의 보존 방안을 먼저 검토한다.
3. Viewer response 연결이 비어 있고 viewer 이벤트에 Lambda@Edge가 없을 때, Functions에서 `peakpic-catalog-canvas-cors`라는 새 함수를 JavaScript runtime 2.0으로 생성한다. 동일 이름이 이미 있으면 그 함수를 덮어쓰지 말고 내용을 먼저 확인한다.
4. 새 함수에 `public-image-cors.js`를 입력하고 저장한다. Test에서 **Viewer response** 이벤트로 Origin 없음 / `https://peakpic.app` / `null`을 각각 확인한다. 응답의 상태·기존 헤더가 유지되고 ACAO 값만 `*`인지 확인한다.
5. 함수 테스트가 통과하면 Publish하여 LIVE로 게시한다. 게시만으로는 배포에 연결되지 않는다.
6. `/catalog/*` behavior의 Viewer response에 이 함수만 연결하고 저장한다. 캐시·원본 요청·응답 헤더 정책, TTL, origin, OAC, 버킷 접근 설정, 다른 함수 및 다른 behavior는 유지한다. 배포 완료 후 아래 실제 GET/브라우저 검증을 수행한다.

[AWS 함수 연결 제한](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/edge-function-restrictions-all.html)에 따라 이벤트 유형마다 함수는 하나만 연결할 수 있다. 또한 원본 응답이 400 이상이면 viewer response 함수가 호출되지 않으므로, 이 방식이 모든 오류 응답에도 ACAO를 보장한다고 표현하면 안 된다. 대상은 실제 존재하는 이미지의 성공 응답이며, 오류 응답에도 필요하면 별도 원본 응답 처리를 검토한다.

전역 invalidation을 기본 절차로 수행하지 않는다. 브라우저에 남은 이미지 캐시로 결과를 오판하지 않도록 검증 때 브라우저 캐시를 비활성화하고, CDN cache hit 응답에서도 함수를 거친 헤더를 확인한다.

## 3. 적용 후 검증

아래 URL에는 실제로 존재하는 CDN 이미지 주소를 넣는다. 루트 경로나 없는 객체의 403/404만으로 성공을 판단하지 않는다. GET으로 상태 200과 `Access-Control-Allow-Origin: *`가 각각 한 번 나오는지 확인한다.

```powershell
$cdnImageUrl = Read-Host '실제 https://di519bna44kn1.cloudfront.net/catalog/... 이미지 URL'
curl.exe --silent --show-error --dump-header - --output NUL "$cdnImageUrl"
curl.exe --silent --show-error --dump-header - --output NUL --header 'Origin: https://peakpic.app' "$cdnImageUrl"
curl.exe --silent --show-error --dump-header - --output NUL --header 'Origin: null' "$cdnImageUrl"
```

첫 요청에는 Origin이 없다. 다음 두 요청은 정상 웹 origin과 리다이렉트에서 발생할 수 있는 null origin을 확인한다. 한 요청을 반복해 CDN cache hit 응답에서도 같은 헤더를 확인한다.

FE에서는 이미지의 `crossOrigin = 'anonymous'`를 `src`보다 먼저 지정한다. API 이미지 URL에서 302를 거쳐 CDN으로 이동하는 전체 흐름을 재현하고, 프레임과 사용자 사진이 포함된 캔버스의 PNG 다운로드까지 확인한다. `*`는 쿠키 등 자격 증명을 포함하지 않는 이미지 요청을 전제로 한다. 실제 검증을 마칠 때까지 FE 프록시를 유지한다.

적용 완료 조건:

- 새 세션 POST와 기존 세션 GET의 shareUrl이 `https://peakpic.app/go/{slug}/{code}`다.
- Origin 없음 / `https://peakpic.app` / `null` 요청에서 CDN 이미지가 200이며 ACAO 값이 `*`다.
- 브라우저에서 API → CDN 리다이렉트 후 캔버스 다운로드가 성공한다.

## 롤백

함수 적용 문제가 있으면 `/catalog/*`의 viewer response 연결을 기록한 이전 상태로 복구한다. 기존 연결이 없었다면 이번에 추가한 연결만 해제한다. 기존 SimpleCORS와 다른 설정, 함수 자체와 데이터는 삭제하지 않는다. 공유 주소 문제가 있으면 직전 운영 설정으로 되돌리고 서버를 재기동한다.
