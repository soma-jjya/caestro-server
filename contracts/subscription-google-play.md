# Peakpic Pro 구독: Google Play 결제 확인 · 계정별 권한 · 실시간 알림

앱(Android)의 Peakpic Pro 는 Google Play 구독 하나(`peakpic_pro`)에 기본 요금제 셋(`weekly` · `monthly` · `yearly`)이다.
앱이 결제하면 구매 토큰을 서버에 보내고, 서버는 Google Play Developer API 로 상태를 확인해 **그 계정의** 구독으로 기록한다.
그 뒤 갱신·해지·환불은 Google 의 실시간 개발자 알림(RTDN)으로 받아 같은 행을 고친다.
권한 판단은 기존 그대로다: `status = ACTIVE` 이고 `expiresAt` 이 지금 이후 (`SubscriptionService#isActivePremium`,
`DailyLimitService` 의 한도 우회도 이것을 쓴다). 그래서 다른 기기·나중의 iOS 에서도 같은 계정이면 Pro 다.

## 인증

- `POST /subscriptions/google-play`, `GET /subscriptions/me`: 앱의 Bearer JWT. 게스트(소셜 연동 전) 계정은 403.
- `POST /subscriptions/google-play/notifications`: JWT 없음(Pub/Sub 푸시). URL 의 `?token=` 이 `google.play.rtdn-token` 과
  같아야 한다(상수 시간 비교). 설정이 비면 항상 403. 알림 본문은 믿지 않고 구매 토큰만 꺼내 Google 에 다시 묻는다.
- `POST /subscriptions` (결제 없이 부여, 기존): `subscription.manual-grant-enabled=true` 일 때만. **기본(운영)은 403.**
  스토어 결제가 생긴 뒤로는 누구나 JWT 만으로 무료 프리미엄이 되는 길이라 막는다. 지금 이 경로를 부르는 클라이언트는 없다.

## 결제 확인

```
POST /subscriptions/google-play
{ "purchaseToken": "…Purchase.getPurchaseToken()…", "productId": "peakpic_pro" }   // productId 는 생략 가능
→ 200 SubscriptionResponse
{
  "active": true,                     // 지금 이용 가능 (만료 시각을 응답 시점에 재계산)
  "plan": "PREMIUM",
  "status": "ACTIVE",                 // ACTIVE | EXPIRED | CANCELED(환불)
  "startedAt": "…", "expiresAt": "…", "createdAt": "…",
  "store": "GOOGLE_PLAY",             // 새 필드. 수동 부여(MANUAL)·이력 없음은 null
  "basePlanId": "monthly",            // 새 필드
  "autoRenewing": true,               // 새 필드. false = 해지 예약(만료일까지 이용 가능)
  "storeState": "SUBSCRIPTION_STATE_ACTIVE"   // 새 필드. Google 의 원래 상태
}
```

- 같은 토큰을 다시 보내면 최신 상태로 고친다(앱의 구매 복원·앱 시작 때 재확인). 멱등.
- 결제 때 앱이 넣는 `obfuscatedAccountId` = 사용자 id(JWT subject 문자열)의 SHA-256 hex. Google 응답의 값이 호출한 계정과
  다르면 409 — 다른 계정으로 산 토큰을 이 계정에 붙이지 못한다. 한 토큰은 한 계정에만 묶인다(유니크).
- Google 이 승인 대기(`ACKNOWLEDGEMENT_STATE_PENDING`)라고 하면 서버도 승인한다(3일 안에 승인 안 되면 Google 이 환불). 앱도 승인한다.
- 결제 대기(`SUBSCRIPTION_STATE_PENDING`, 느린 결제 수단)는 행을 남기되 `active=false`. 결제가 끝나면 RTDN 이 ACTIVE 로 바꾼다.

### Google 상태 → status

| Google `subscriptionState` | status | 비고 |
|---|---|---|
| ACTIVE · IN_GRACE_PERIOD · CANCELED | ACTIVE | 만료 시각이 지나면 isActive 가 false. CANCELED 는 해지 예약 |
| ON_HOLD · PAUSED · EXPIRED · PENDING · 그 밖 | EXPIRED | |
| (voided 알림: 환불·지불 거절) | CANCELED | 만료를 지금으로 당기고, 이후 활성 알림이 와도 되살리지 않음 |
| (요금제 변경으로 새 토큰이 대신함) | EXPIRED | storeState `REPLACED`, 새 행이 권한을 이어받음 |

## 오류

| 상태 | ErrorCode | 언제 |
|---|---|---|
| 400 | SUBSCRIPTION_PURCHASE_INVALID | Google 이 모르는 토큰(400/404/410), 다른 상품, 형식이 틀린 토큰 |
| 403 | GUEST_ACCOUNT_NOT_ALLOWED | 게스트 계정 |
| 403 | SUBSCRIPTION_MANUAL_GRANT_DISABLED | `POST /subscriptions` 가 꺼져 있음 |
| 409 | SUBSCRIPTION_PURCHASE_OWNED_BY_OTHER | 토큰이 이미 다른 계정 것 / 결제의 계정 해시가 다름 |
| 503 | SUBSCRIPTION_VERIFICATION_UNAVAILABLE | 서비스 계정 키 미설정, Google 5xx·네트워크 (앱은 나중에 다시 보냄) |

## 실시간 알림 (RTDN)

`POST /subscriptions/google-play/notifications?token=…` — Pub/Sub 푸시 봉투 `{"message":{"data":"<base64>","messageId":"…"},"subscription":"…"}`.

- `subscriptionNotification` (모든 notificationType): 그 토큰을 Google 에 다시 물어 행을 고친다.
  모르는 토큰이면 `linkedPurchaseToken`(요금제 변경·재구독)의 행을 찾아 같은 계정으로 새 행을 만들고 이전 행은 REPLACED.
  그것도 없으면 아무것도 하지 않는다(앱의 확인 요청이 계정에 묶는다).
- `voidedPurchaseNotification` (productType 1 = 구독): 그 행을 CANCELED 로 끝낸다.
- `testNotification`, 다른 패키지, 읽을 수 없는 메시지: 204 로 끝낸다.
- 응답: 처리·무시 모두 204. Google 에 물을 수 없을 때만 503 (Pub/Sub 이 백오프로 다시 보낸다).

## 처리 순서와 격리 (#184)

결제 확인과 알림 처리는 세 토막이다 — Google 응답을 기다리는 동안 DB 커넥션을 쥐지 않기 위해서다(전에는 메서드 전체가 한 트랜잭션이라
Google 이 느리면 요청 하나가 커넥션을 최대 8초 점유했다).

1. **읽기 트랜잭션**: 계정·게스트·토큰 소유자 검사. Google 을 부르기 전에 끝낼 수 있는 거절(400·403·409)은 여기서 끝낸다.
2. **트랜잭션 없이 Google 조회**. 서킷브레이커 `google-play`(`ExternalApiGuard.noRetry`, 재시도 없음) 뒤에 있어 Google 이 연속 실패하면
   10초 동안 기다리지 않고 바로 503(확인) · 503 재전송(알림)이다. 사용자가 기다리는 호출이라 재시도는 하지 않는다.
3. **짧은 쓰기 트랜잭션**: 토큰의 행을 찾거나 만들어 상태를 덮어쓴다. 같은 토큰의 요청 둘이 동시에 INSERT 해 유니크
   (`uk_subscriptions_purchase_token`)에 걸리면 새 트랜잭션에서 한 번 다시 읽어 그 행에 반영한다 — 둘 다 200.
4. 커밋 뒤에 Google 승인(acknowledge). 실패해도 행은 남고 앱도 승인한다.

지표(Prometheus 이름): `subscription_verify_total{store="google_play",result}` result = ok · invalid · rejected · conflict · unavailable · error,
`subscription_notification_total{store="google_play",result}` result = applied · ignored · unavailable.
서킷 상태는 `resilience4j_circuitbreaker_state{name="google-play"}`.

## 설정

```yaml
subscription:
  manual-grant-enabled: false
google:
  play:
    package-name: soma.project.caestro
    product-id: peakpic_pro
    service-account-json: ""   # 서비스 계정 키(JSON 원문 또는 base64). 비면 확인 503
    rtdn-token: ""             # Pub/Sub 푸시 URL 의 ?token=. 비면 알림 403
```

운영: Secrets Manager `peakpic/prod/app`에 `google.play.service-account-json`(키 JSON 의 base64 한 줄), `google.play.rtdn-token`(난수)을 넣는다.
서버가 기동할 때 읽으므로 값을 바꾸면 배포(재기동)가 필요하다. 키가 잘못돼도 서버는 뜨고 확인만 503 이다.
GitHub Secret → build-args → Dockerfile ENV 경로는 #188 에서 없앴다 — 빌드 인자는 이미지 설정과 공개 저장소의 빌드 증명에 평문으로 남는다.

### 처음 한 번 (콘솔)

1. Google Cloud 프로젝트에서 **Google Play Android Developer API** 를 켜고 서비스 계정을 만들어 JSON 키를 받는다.
2. Play Console › 사용자 및 권한 › 새 사용자 초대: 서비스 계정 이메일, 앱 권한 **재무 데이터 보기**, **주문 및 구독 관리**.
3. Pub/Sub 주제(예: `play-rtdn`)를 만들고 `google-play-developer-notifications@system.gserviceaccount.com` 에 게시자 역할을 준다.
4. Play Console › 수익 창출 설정 › 실시간 개발자 알림: 주제 `projects/<프로젝트>/topics/play-rtdn`, 테스트 알림 보내기.
5. 그 주제에 **푸시** 구독: 엔드포인트 `https://api.peakpic.app/subscriptions/google-play/notifications?token=<google.play.rtdn-token>`.

## DDL (ddl-auto 가 update 가 아닌 환경용)

```sql
ALTER TABLE subscriptions
  ADD COLUMN updated_at      DATETIME(6)  NULL,
  ADD COLUMN store           VARCHAR(20)  NULL,
  ADD COLUMN purchase_token  VARCHAR(512) NULL,
  ADD COLUMN product_id      VARCHAR(100) NULL,
  ADD COLUMN base_plan_id    VARCHAR(40)  NULL,
  ADD COLUMN store_state     VARCHAR(50)  NULL,
  ADD COLUMN auto_renewing   BIT(1)       NULL,
  ADD COLUMN latest_order_id VARCHAR(100) NULL,
  ADD CONSTRAINT uk_subscriptions_purchase_token UNIQUE (purchase_token);
```

기존 행(수동 부여)은 새 열이 모두 NULL 이고 그대로 동작한다. `store` 는 값이 늘어도 ALTER 가 없도록 MySQL ENUM 이 아닌 VARCHAR.

## 클라이언트 (Android)

- 결제 성공·앱 시작·구매 복원 때 활성 구매마다 `POST /subscriptions/google-play`. 2xx 면 응답의 `active` 를 계정 권한으로 쓴다.
  503·네트워크 오류는 다음 기회에 다시 보낸다. 404(이 API 가 없는 서버)는 조용히 넘긴다.
- 앱 시작·결제 화면을 열 때 `GET /subscriptions/me`. `store` 가 있는(스토어 결제로 확인된) 활성 구독이면 이 기기에 Play 구매가 없어도 Pro.
- Pro = 이 기기의 Play 구독 OR 서버의 계정 구독. 무료 한도(네컷 하루 3회·나만의 프레임 1개)는 아직 기기에서 센다.
