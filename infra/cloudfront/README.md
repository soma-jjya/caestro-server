# B2B 공유 주소와 CDN 이미지 CORS

2026-10-28 요청의 적용 절차다. 이 파일과 JSON을 커밋하는 것만으로 AWS 설정은 바뀌지 않는다. 실제 배포의 정책 ID, 기존 헤더 설정 및 AWS 적용 결과는 아직 확인하지 않았다.

## 1. 공유 주소

운영 서버의 유효 설정을 다음으로 맞춘다.

```yaml
b2b:
  web-base-url: https://peakpic.app/go
```

환경 변수 이름은 `B2B_WEB_BASE_URL`이다. 저장소의 application.yml과 서비스 기본값은 위 주소를 사용한다. 운영 환경 변수, application-prod.yml 또는 Secrets Manager(`peakpic/prod/app`)에서 과거 값을 지정했다면 해당 항목도 수정한다. 다른 시크릿은 유지한다. 기동 시 읽는 값이므로 설정 반영 후 인스턴스를 교체하고 실제 완료를 확인한다.

응답 예시: `https://peakpic.app/go/yuja/K7X2MQ`. 생성 POST와 기존 세션 GET 모두 현재 설정으로 shareUrl을 만든다. DB 마이그레이션은 필요 없다. 이미 배포된 예전 주소의 리다이렉트 처리는 FE 라우팅에서 관리한다.

## 2. CloudFront 응답 헤더

- 배포 도메인: `di519bna44kn1.cloudfront.net`.
- 현재 코드의 대상 객체 경로: `/catalog/*`. B2B 프레임 사본도 CatalogAssetStore의 같은 경로를 사용한다. 운영에서 key-prefix를 바꿨다면 실제 이미지 경로와 매칭되는 behavior를 확인한다.
- 추가할 **응답** 헤더: `Access-Control-Allow-Origin: *`, **Override = true**.
- 위치: Response headers policy의 **Custom headers**. Origin 요청 헤더 유무와 무관하게 추가해야 하므로 CORS 섹션의 조건부 처리에만 의존하지 않는다.

[AWS 문서](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/understanding-response-headers-policies.html#understanding-response-headers-policies-custom)는 Custom headers를 모든 viewer 응답에 추가한다고 명시한다. Override=true는 원본 서버의 같은 헤더도 지정한 값으로 덮어쓴다.

1. AWS CloudFront 콘솔에서 위 도메인의 배포를 선택한다. Behaviors에서 실제 `/catalog/...` 이미지 요청에 매칭되는 항목과 현재 Response headers policy ID를 기록한다.
2. 현재 정책이 다른 배포/경로에도 연결돼 있다면 이 이미지 경로용 정책으로 복제한다. 기존 security/custom/remove 헤더 설정을 보존한다.
3. Custom headers에 `Access-Control-Allow-Origin` = `*`, Override=true를 설정한다. 같은 헤더를 CorsConfig와 중복 정의하지 않는다. 기존 CORS 섹션을 대체해야 하면 필요한 나머지 CORS 헤더도 검토해 custom 설정으로 이관한다.
4. 이미지 behavior에 새 정책을 연결한다. 캐시 키, TTL, origin, OAC, 버킷 공개 여부 및 다른 behavior는 유지한다.
5. 배포 상태가 Deployed가 될 때까지 확인하고 아래 GET 검증을 실행한다.

정책이 없는 behavior용 최소 JSON은 [public-image-response-headers.json](public-image-response-headers.json)이다. **기존 정책을 이 최소 JSON으로 통째로 덮어쓰면 안 된다.** 기존 정책이 있다면 위 절차대로 필요한 헤더를 보존한 사본에 병합한다.

현재 정책이 없음을 확인했고 이 최소 정책을 새로 생성할 경우:

```powershell
aws cloudfront create-response-headers-policy --response-headers-policy-config file://infra/cloudfront/public-image-response-headers.json
```

생성한 정책을 해당 behavior에 연결해야 효력이 생긴다. 이 명령은 배포 연결까지 수행하지 않는다. 기존 보안·캐시 정책의 보존은 AWS에서 원본 설정을 읽은 뒤 확인한다.

응답 헤더 정책은 캐시에서 반환하는 응답에도 적용된다. 정책 수정만을 이유로 전역 invalidation을 실행하지 않는다. 브라우저에 남은 이미지 캐시로 결과를 오판하지 않도록 검증 때 브라우저 캐시를 비활성화한다. [AWS 설명](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/modifying-response-headers.html)

## 3. 적용 후 검증

아래 URL에는 실제로 존재하는 CDN 이미지 주소를 넣는다. 루트 경로나 없는 객체의 403/404만으로 성공을 판단하지 않는다. GET으로 상태 200과 `Access-Control-Allow-Origin: *`가 각각 한 번 나오는지 확인한다.

```powershell
$cdnImageUrl = Read-Host '실제 https://di519bna44kn1.cloudfront.net/catalog/... 이미지 URL'
curl.exe --silent --show-error --dump-header - --output NUL "$cdnImageUrl"
curl.exe --silent --show-error --dump-header - --output NUL --header 'Origin: https://peakpic.app' "$cdnImageUrl"
curl.exe --silent --show-error --dump-header - --output NUL --header 'Origin: null' "$cdnImageUrl"
```

첫 요청에는 Origin이 없다. 다음 두 요청은 정상 웹 origin과 리다이렉트에서 발생할 수 있는 null origin을 확인한다. 한 요청을 반복해 CDN cache hit 응답에서도 같은 헤더를 확인한다.

FE에서는 이미지의 `crossOrigin = 'anonymous'`를 `src`보다 먼저 지정한다. API 이미지 URL에서 302를 거쳐 CDN으로 이동하는 전체 흐름을 재현하고, 프레임과 사용자 사진이 포함된 캔버스의 PNG 다운로드까지 확인한다. 이 정책의 `*`는 쿠키 등 자격 증명을 포함하지 않는 이미지 요청을 전제로 한다.

적용 완료 조건:

- 새 세션 POST와 기존 세션 GET의 shareUrl이 `https://peakpic.app/go/{slug}/{code}`다.
- Origin 없음 / `https://peakpic.app` / `null` 요청에서 CDN 이미지가 200이며 ACAO 값이 `*`다.
- 브라우저에서 API → CDN 리다이렉트 후 캔버스 다운로드가 성공한다.

## 롤백

응답 헤더 정책 문제가 있으면 기록한 이전 policy ID를 동일 behavior에 다시 연결한다. 공유 주소 문제가 있으면 직전 운영 설정으로 되돌리고 서버를 재기동한다. 정책과 데이터는 임의 삭제하지 않는다.
