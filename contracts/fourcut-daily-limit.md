# 무료 하루 한도: 네컷 저장 하루 3회

무료 사용자는 완성한 네컷을 하루 3번까지 앨범에 저장한다. 같은 네컷을 다시 저장하거나 편집하는 것은 세지 않는다.
프리미엄 구독 중이면(`SubscriptionService#isActivePremium`, Google Play 구독 확인은 contracts/subscription-google-play.md) 한도도 기록도 없다.
지금까지는 앱이 기기에서만 세서 앱을 지우면 다시 3회가 됐다. 이제 계정(게스트 포함) 기준으로 서버가 센다.

## API (Bearer JWT, 게스트 계정도 가능)

```
GET /limits/fourcut
→ 200 { "allowed": true, "unlimited": false, "limit": 3, "used": 1, "remaining": 2,
        "date": "2026-10-23", "resetsAt": "2026-10-24T00:00:00+09:00" }

POST /limits/fourcut        { "projectId": "a1b2c3d4e5f6" }     // 앱의 네컷 id, [A-Za-z0-9._-]{1,64}
→ 200 같은 모양. allowed = 이번 저장이 허용됐는가
```

- 앱은 네컷을 앨범에 **처음** 저장하기 직전에 POST 한다. `allowed=false` 면 저장하지 않고 결제 화면을 보인다.
- 같은 `projectId` 는 그날 한 번만 센다: 재시도·다시 저장은 `allowed=true` 로 다시 허용(횟수 그대로).
- 한도가 차면 기록하지 않고 `allowed=false` (HTTP 200 — 앱이 한 모양으로 남은 횟수를 그린다).
- 프리미엄: `allowed=true, unlimited=true`, limit·used·remaining 은 null.
- 하루는 `limits.zone`(기본 Asia/Seoul) 자정에 바뀐다. 서버 시계(UTC)와 무관.

## 저장

새 테이블 `daily_usages` — 쓸 때마다 한 행.

- 유니크 (user_id, usage_date, action_type, usage_key): 같은 네컷 두 번 세지 않기
- 유니크 (user_id, usage_date, action_type, slot): slot = 그날 몇 번째(1..한도). 동시에 저장해도 한도를 넘는 행이 생기지 않는다(잠금 없이).
  충돌하면 새 트랜잭션에서 다시 읽어 판단한다(최대 3번).
- 정리 배치(`cleanup.daily-usage`, 매일 05:10, ShedLock): 30일 지난 기록 삭제.

기존 `shot_daily_limits` / `DailyLimitService`(사용자·날짜당 숫자 하나)는 동작을 구분하지 못하고 재시도에 두 번 세어져 쓰지 않았다.
지금 부르는 곳이 없으니 이 PR 이 들어가면 지워도 된다(이 PR 에서는 건드리지 않음).

## 설정

```yaml
limits:
  zone: Asia/Seoul
  fourcut-save:
    daily-free: 3
cleanup:
  daily-usage:
    cron: "0 10 5 * * *"
    retention-days: 30
```

## DDL (ddl-auto 가 update 가 아닌 환경용)

```sql
CREATE TABLE daily_usages (
  id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id     BIGINT       NOT NULL,
  usage_date  DATE         NOT NULL,
  action_type VARCHAR(30)  NOT NULL,
  slot        INT          NOT NULL,
  usage_key   VARCHAR(64)  NOT NULL,
  created_at  DATETIME(6)  NULL,
  CONSTRAINT uk_daily_usages_key  UNIQUE (user_id, usage_date, action_type, usage_key),
  CONSTRAINT uk_daily_usages_slot UNIQUE (user_id, usage_date, action_type, slot),
  CONSTRAINT fk_daily_usages_user FOREIGN KEY (user_id) REFERENCES users (id),
  INDEX idx_daily_usages_date (usage_date)
);
```

## 클라이언트 (Android)

- 네컷 시트를 열 때 GET 으로 '남은 기회 n/3' 을 그린다.
- 처음 저장 직전에 POST. 서버에 닿지 못하면(오프라인) 기기의 카운트로 판단하고, 그 네컷 id 를 기억했다가 다음에 POST 로 맞춘다.
- 회원 탈퇴 시 users 행과 함께 지워져야 한다면 탈퇴 처리에서 이 테이블도 함께 지운다(현재 탈퇴는 soft delete 라 FK 문제 없음).
