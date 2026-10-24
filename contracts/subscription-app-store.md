# Peakpic Pro 구독: App Store 결제 확인 · 계정별 권한 · 서버 알림

iOS 의 Peakpic Pro 는 App Store 구독 그룹 `Peakpic Pro` 에 상품 셋(`peakpic_pro_yearly` · `peakpic_pro_monthly` · `peakpic_pro_weekly`)이다.
Google Play 는 상품 하나에 기본 요금제 셋이지만 App Store 는 기간마다 상품이 따로라, 상품 id 는 Play 상품 id 와 요금제 id 를 이은 것이고
서버가 기록하는 `basePlanId` 는 두 스토어가 같다(`yearly` · `monthly` · `weekly`).
앱이 결제하면 StoreKit 2 의 서명된 거래를 서버에 보내고, 서버는 App Store Server API 로 상태를 확인해 **그 계정의** 구독으로 기록한다.
그 뒤 갱신·해지·환불은 App Store Server Notifications V2 로 받아 같은 행을 고친다.
권한 판단·행 모양은 Google Play 와 같다 (contracts/subscription-google-play.md): `status = ACTIVE` 이고 `expiresAt` 이 지금 이후.
그래서 아이폰에서 산 Pro 도 같은 계정이면 안드로이드에서 Pro 다(`GET /subscriptions/me`).

## 인증

- `POST /subscriptions/app-store`: 앱의 Bearer JWT. 게스트(소셜 연동 전) 계정은 403 — Google Play 와 같다.
  iOS 앱은 게스트도 결제할 수 있고(App Review 5.1.1) 그 기기에서는 App Store 가 Pro 를 지킨다. 로그인 뒤 앱이 다시 보내면 그때 계정에 묶인다.
- `POST /subscriptions/app-store/notifications`: JWT 없음(Apple 이 보낸다). URL 의 `?token=` 이 `apple.app-store.notification-token` 과
  같아야 한다(상수 시간 비교). 설정이 비면 항상 403. 알림 본문은 믿지 않고 거래 번호만 꺼내 Apple 에 다시 묻는다.

## 결제 확인

```
POST /subscriptions/app-store
{ "signedTransaction": "…VerificationResult<Transaction>.jwsRepresentation…", "productId": "peakpic_pro_monthly" }   // productId 는 생략 가능
→ 200 SubscriptionResponse
{
  "active": true, "plan": "PREMIUM", "status": "ACTIVE",
  "startedAt": "…", "expiresAt": "…", "createdAt": "…",
  "store": "APP_STORE",
  "basePlanId": "monthly",
  "autoRenewing": true,               // false = 해지 예약(만료일까지 이용 가능)
  "storeState": "ACTIVE"              // ACTIVE · EXPIRED · BILLING_RETRY · BILLING_GRACE_PERIOD · REVOKED
}
```

- 앱이 보낸 JWS 는 서명을 확인하지 않는다: `transactionId`(없으면 `originalTransactionId`, 숫자만)를 꺼내
  App Store Server API `GET /inApps/v1/subscriptions/{transactionId}`(Get All Subscription Statuses)로 Apple 에 직접 묻는다.
  운영(api.storekit.itunes.apple.com)에 먼저 묻고, 모르는 거래면 샌드박스(TestFlight·개발 빌드)에 묻는다(`apple.app-store.environment: auto`).
- 행의 `purchase_token` = `originalTransactionId` (갱신·요금제 변경에도 같은 번호). 한 번호는 한 계정에만 묶인다(유니크). 같은 구독 재전송은 최신 상태로 갱신(멱등).
- 결제 때 앱이 넣는 `appAccountToken` = 사용자 id(JWT subject 문자열)의 SHA-256 앞 16바이트를 UUID 로(버전 4·변형 비트 표시).
  Google Play 의 `obfuscatedAccountId`(같은 해시의 hex)와 같은 계정 표시다. Apple 응답의 값이 호출한 계정과 다르면 409.
  값이 없는 거래(계정 표시 없이 산 것)는 호출한 계정에 묶는다.
- 번들 id 가 `soma.project.caestro` 가 아니거나 상품이 `peakpic_pro_` 로 시작하지 않으면 400.

### Apple 상태 → status

| Apple `status` (lastTransactions) | status | 비고 |
|---|---|---|
| 1 구독 중 · 4 결제 유예 | ACTIVE | 만료 시각이 지나면 isActive 가 false. 해지 예약도 1(구독 중)로 오고 `autoRenewing=false` |
| 2 만료 · 3 결제 재시도 · 그 밖 | EXPIRED | |
| 5 철회 / `revocationDate` 있음 (환불·가족 공유 해제) | CANCELED | 만료를 지금으로 당기고, 이후 활성 상태가 와도 되살리지 않음 (Google Play 의 voided 와 같다) |

## 오류

Google Play 와 같은 ErrorCode 를 쓴다.

| 상태 | ErrorCode | 언제 |
|---|---|---|
| 400 | SUBSCRIPTION_PURCHASE_INVALID | 읽을 수 없는 거래, Apple 이 모르는 거래(400/404), 다른 앱·상품 |
| 403 | GUEST_ACCOUNT_NOT_ALLOWED | 게스트 계정 |
| 409 | SUBSCRIPTION_PURCHASE_OWNED_BY_OTHER | 구독이 이미 다른 계정 것 / 결제의 계정 토큰이 다름 |
| 503 | SUBSCRIPTION_VERIFICATION_UNAVAILABLE | 인앱 결제 키 미설정, Apple 401·429·5xx·네트워크 (앱은 나중에 다시 보냄) |

## 서버 알림 (App Store Server Notifications V2)

`POST /subscriptions/app-store/notifications?token=…` — 본문 `{"signedPayload": "<JWS>"}`.

- 모든 notificationType(SUBSCRIBED · DID_RENEW · DID_CHANGE_RENEWAL_STATUS · DID_FAIL_TO_RENEW · GRACE_PERIOD_EXPIRED · EXPIRED · REFUND · REVOKE …):
  `data.signedTransactionInfo` 의 `originalTransactionId` 로 행을 찾아, 있으면 Apple 에 다시 물어 고친다.
  행이 없으면 아무것도 하지 않는다(앱의 확인 요청이 계정에 묶는다 — `appAccountToken` 으로는 계정을 거꾸로 찾을 수 없다).
- `TEST`, 다른 번들, 읽을 수 없는 알림: 받은 것으로 끝낸다.
- 응답: 처리·무시 모두 200. Apple 에 물을 수 없을 때만 503 (Apple 이 간격을 두고 다시 보낸다).

## 설정

```yaml
apple:
  app-store:
    bundle-id: soma.project.caestro
    product-prefix: peakpic_pro_
    issuer-id: ""          # App Store Connect › 사용자 및 액세스 › 통합 › 인앱 결제 의 Issuer ID
    key-id: ""             # 같은 곳에서 만든 인앱 결제 키의 ID
    private-key: ""        # 그 키의 .p8 (PEM 원문 또는 머리줄을 뺀 한 줄 base64). 셋 중 하나라도 비면 확인 503
    environment: auto      # auto(운영 → 샌드박스) | production | sandbox
    notification-token: "" # 알림 URL 의 ?token=. 비면 알림 403
```

로그인용 애플 키(`apple.key-id` · `apple.private-key`, Sign in with Apple)와는 **다른 키**다. App Store Server API 는 인앱 결제 키만 받는다.

운영: Secrets Manager `peakpic/prod/app`에 `apple.app-store.issuer-id` · `apple.app-store.key-id` · `apple.app-store.private-key`(.p8 본문 한 줄) ·
`apple.app-store.notification-token`(난수)을 넣는다. 기동 때 읽으므로 값을 바꾸면 배포가 필요하다. 비어 있어도 서버는 뜬다.
GitHub Secret → build-args → Dockerfile ENV 경로는 #188 에서 없앴다 (Google Play 와 같은 이유).

### 처음 한 번 (App Store Connect)

1. 사용자 및 액세스 › 통합 › 인앱 결제: 키를 만들고 .p8 을 받는다(한 번만 받을 수 있다). Issuer ID 와 키 ID 를 적는다.
2. 앱 › 구독: 그룹 `Peakpic Pro`, 상품 `peakpic_pro_yearly` · `peakpic_pro_monthly` · `peakpic_pro_weekly` (20,500 · 2,790 · 990원).
3. 앱 › App 정보 › App Store 서버 알림: 버전 2, 운영·샌드박스 URL
   `https://api.peakpic.app/subscriptions/app-store/notifications?token=<apple.app-store.notification-token>`. 등록 화면의 테스트 알림으로 확인.

### 운영 반영 기록 (2026-10-23)

- App Store Connect (사용자): 구독 그룹 `Peakpic Pro`(그룹 ID 22499460, 표시 이름 한국어·영어(미국) `Peakpic Pro`, 앱 이름 peakpic),
  상품 등급 1 `peakpic_pro_yearly` · 2 `peakpic_pro_monthly` · 3 `peakpic_pro_weekly`, 가족 공유 끔(한 거래 = 한 계정이라), 유입 혜택 없음,
  현지화 한국어·영어(미국), 심사용 스크린샷(iPhone 6.9″ 결제 화면). 첫 구독이라 새 앱 버전과 함께 심사 제출해야 한다.
- 인앱 결제 키: Key ID `L426UQ2V98`. 키·Issuer ID·번들로 서명한 토큰으로 `POST /inApps/v1/notifications/test`(운영·샌드박스)를 불러
  `4040007 No App Store Server Notification URL` — 인증은 통과(틀리면 401), 알림 URL 만 아직 없음.
- GitHub Secret 4개 등록(`APPLE_APP_STORE_*`). 값은 어디에도 기록하지 않는다. 비밀키는 .p8 본문 한 줄 base64(build-args 가 줄 단위라).
  알림 토큰은 난수 48자, 사용자 Mac 의 권한 600 파일에만 URL 과 함께 있다.
- 샌드박스 결제(iOS 개발 빌드, 샌드박스 테스터): 1년 구매 → Pro, 구독 취소·다시 갱신, 1년 → 1주 변경(다른 등급이라 다음 갱신부터) 확인.
- 남은 것: App Store Connect › App 정보 › App Store 서버 알림에 운영·샌드박스 URL 등록(사용자) → 등록 화면 또는 위 test API 로 TEST 알림 확인.
- 참고: 같은 날 확인한 저장소 Secret 에 `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` · `GOOGLE_PLAY_RTDN_TOKEN` 이 없다 — 운영의 Google Play 확인은 503,
  RTDN 은 403 인 상태(contracts/subscription-google-play.md 의 설정 필요).

### 운영 반영 기록 (2026-10-25, #188)

- 비밀값 주입 경로를 바꿨다: Dockerfile ARG/ENV 와 deploy.yml build-args 를 지우고, 값은 Secrets Manager `peakpic/prod/app`에 넣는다.
  이유: 빌드 인자로 넣은 값은 이미지 설정(`docker inspect`)과 공개 저장소의 빌드 증명에 평문으로 남아, 이미지를 받을 수 있으면 읽힌다.
- 인앱 결제 키는 새로 발급해 교체했고 알림 토큰도 새 난수로 바꿨다. 스튜디오 키도 같이 교체.
- 남은 것(배포 뒤 순서대로): 컨테이너 환경변수에 접두사 없음 확인 → 옛 키 `L426UQ2V98` 폐기 → GitHub Secret `APPLE_APP_STORE_*`·`CATALOG_STUDIO_KEY` 삭제
  → 10/22 이후 ECR 이미지 삭제 → 알림 URL 등록(새 토큰) → TEST 알림 확인.

## DDL

없음. `subscriptions.store` 는 VARCHAR 라 `APP_STORE` 를 더해도 ALTER 가 필요 없다(contracts/subscription-google-play.md 의 열을 그대로 쓴다).

## 클라이언트 (iOS)

- 결제 성공·앱 시작·구매 복원·App Store 의 거래 변경(Transaction.updates) 때, 현재 유효한 거래마다 `POST /subscriptions/app-store`. 2xx 면 응답을 계정 권한으로 쓴다.
  403(게스트)·404(이 경로가 없는 서버)·503·네트워크 오류는 넘기고 다음 기회에 다시 보낸다.
- 앱 시작·결제 화면을 열 때 `GET /subscriptions/me`. `store` 가 있는(스토어 결제로 확인된) 활성 구독이면 이 기기에 App Store 구매가 없어도 Pro.
- Pro = 이 Apple ID 의 App Store 구독 OR 서버의 계정 구독.
