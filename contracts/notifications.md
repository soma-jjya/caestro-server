# 알림함: 저장 · 목록 · 읽음 (#211)

푸시 1단계(contracts/push.md)는 FCM 에 넘기고 끝이라 기록이 없었다. 이제 **보내기 전에 먼저 저장**한다. 푸시를 놓친 사용자도 앱을 열면 여기서 보고, 종 아이콘은 안 읽은 개수로 빨간 점을 찍는다.

## 인증·경로

모두 `Authorization: Bearer` 필수(게스트 포함). 본인 알림만 보이고, 남의 id 는 404 `NOTIFICATION_NOT_FOUND`.

| 메서드 | 경로 | 응답 |
|---|---|---|
| GET | `/notifications?cursor=&size=` | `{ items: [...], nextCursor }` 최신순. `size` 기본 20, 최대 50. `nextCursor` 가 null 이면 끝 |
| GET | `/notifications/unread-count` | `{ unread }` |
| PATCH | `/notifications/{id}/read` | 204. 이미 읽었어도 204 |
| PATCH | `/notifications/read-all` | 204 |

항목 한 줄:

```json
{ "id": 42, "type": "fill_completed", "title": "네컷이 완성됐어요", "body": "친구들이 네 칸을 모두 채웠어요. 지금 확인해 보세요.",
  "data": { "code": "K7X2MQ" }, "createdAt": "2026-11-02T12:00:00", "readAt": null }
```

`type` 과 `data` 는 푸시의 것과 같다. 앱은 푸시 탭과 목록 탭에 **같은 이동 규칙**을 쓴다(`fill_completed` → `data.code` 세션).
`support_reply`(#263) 는 `data.ticketId` 로 앱 문의 상세(contracts/support.md)로 간다.

## 앱 동작

- 홈에 올 때 `unread-count` 를 불러 종 아이콘에 점·개수. 종을 누르면 목록.
- 목록 항목 탭 → `PATCH /{id}/read` → `data` 로 이동. 푸시 탭 → 푸시 `data.notificationId` 로 같은 호출 → 이동.
- "모두 읽음" 버튼은 `read-all`.

## 서버 동작 (읽는 순서) — #272 아웃박스

1. 보내는 쪽(네 칸 완성·만료 안내 배치·문의 답변)은 `NotificationOutboxService.enqueue(userId, PushMessage, options)` 하나만 부른다. 이 호출은 **사건과 같은 트랜잭션**에 `notification_outbox` 행을 넣는다 — 커밋되면 요청도 반드시 남고, 롤백되면 같이 사라진다. 네 칸 완성·문의 답변은 도메인 이벤트를 받는 동기 `@EventListener`(`OutboxEnqueuer`)가 넣고, 만료 안내는 배치가 "보냈음" 표시와 같은 짧은 트랜잭션에 넣는다.
2. `OutboxRelay` 가 인스턴스마다 3초에 한 번 보낼 차례인 행(PENDING, not_before ≤ now)을 집는다. 집기는 행 단위 UPDATE(PENDING → PROCESSING + lease)라 두 릴레이가 같은 행을 보내지 않는다. 보내는 동안 DB 트랜잭션은 쥐지 않는다.
3. 행마다: 유효 시한(`expires_at`)이 지났으면 EXPIRED → 알림함 행을 **한 번만** 만들고 id 를 행에 적는다(재시도에도 알림함은 한 줄) → `PushService.sendReport` 로 FCM. 기기가 있는데 전부 실패(FCM 5xx·서킷 열림)면 attempts+1, `not_before` 를 1·5·30분·2시간·6시간 뒤로 미루고 PENDING 으로. 5회째면 DEAD(로그 error, 사람이 본다). 기기가 없거나 하나라도 수락되면 SENT.
4. 릴레이가 보내는 중에 죽으면 행은 PROCESSING 으로 남고, lease(60초)가 지나면 다음 주기에 PENDING 으로 되찾는다 → **최소 한 번** 전달. 두 번은 `dedup_key` 유니크와 SENT 상태가 막는다.
5. 읽음은 `readAt` 시각. 처음 읽을 때만 `notification.opened` 를 센다.
6. 보관: 알림함 행은 `cleanup.notification`(기본 90일), 아웃박스는 `cleanup.outbox`(SENT·EXPIRED·CANCELLED 7일, DEAD 30일).

### 전달 — Redis Streams (#273, `notification.stream.enabled`, 기본 켬)

```
릴레이(3초) ─ PENDING 집기 → XADD notifications {outboxId} → 행 RELAYED(lease 12시간)
소비자 그룹 notifier (서버마다 1, 블로킹 XREADGROUP)
   → 행 읽기 → 이미 끝났으면 XACK 만(중복 방지)
   → 시한 지남 EXPIRED / 알림함 한 번 → FCM
   ├ 성공·만료 → XACK
   ├ 실패 → attempts+1, ack 하지 않음(PEL 에 남음)
   └ 한도(5) → notifications.dead 에 XADD + 행 DEAD + XACK
스윕(30초) ─ XPENDING → 행 attempts 만큼의 백오프(처음은 60초)를 넘긴 것 XCLAIM → 다시 처리
```

- 메시지에는 `outboxId` 만 싣는다. 내용·개인정보는 DB 에 있고 큐가 날아가도 DB 가 원본이다(RELAYED 가 12시간 끝나지 않으면 릴레이가 PENDING 으로 되찾아 다시 넣는다).
- 소비자 이름은 기동마다 새로(`호스트-난수`). 배포로 사라진 소비자의 PEL 은 다른 서버의 스윕이 60초 뒤 넘겨받는다.
- Redis 가 안 되면 XADD 가 실패해 행은 PROCESSING(lease 60초) → PENDING 으로 돌아와 다음 바퀴에 다시. 요청은 잃지 않는다.
- 끄면(`false`) 릴레이가 직접 FCM 을 부르고 실패는 `not_before` 백오프로 다시(#272 방식). 롤백은 이 한 줄.
- 지표: `notification.consumed{type,result=sent|expired|cancelled|retried|dead|skipped}`, `notification.stream.pending`(꺼냈지만 못 끝낸 수), `notification.stream.dead`(보류함 길이).
- 테스트 JVM 은 워커(릴레이 주기·소비자)를 끈다(build.gradle). 켜는 건 `NotificationStreamTest` 하나.

### 묶음 알림 — 친구가 칸을 채웠어요 (#274, `fill_progress`)

```
친구가 칸 채움 (칸을 채운 트랜잭션) ─ upsert: 이 세션 대기 행이 없으면 새로(5분 뒤), 있으면 합침(merged_count+1, 5분 더 미룸)
                                      단 처음 넣은 때 + 1시간을 넘겨 미루지 않음 / 직전에 보냈으면 보낸 때 + 1시간까지 기다림
네 칸 완성 (같은 트랜잭션)          ─ 대기 중인 진행 행 CANCELLED → 주인은 "완성됐어요" 한 통만
릴레이가 집는 순간                   ─ 행의 키를 닫는다(:open → :open#id). 이 뒤의 채움은 다음 묶음
보내는 순간 (OutboxDelivery)         ─ FillProgressRenderer 가 세션을 다시 읽어 문구. 완성됐거나 세션이 없으면 CANCELLED
```

- **누구에게**: 주인에게만. 주인이 직접 채운 칸은 알림을 만들지 않고 친구 몫에서도 빠진다(칸 수에는 든다). 완성 뒤에는 칸을 바꾸든, 비웠다가 다시 채우든, 대기열에서 되돌리든 조용하다 — 진행 알림도 두 번째 완성 알림도 없다(`completed_notified_at` 은 한 번 찍히면 지우지 않는다, `FillProgressPushTest` 가 지킨다). 대기열에서 되돌리기(`restoreQueued`)는 새 사진이 아니라 알리지 않는다.
- **문구** (보내는 순간의 세션):
  - 제목: `민지님이 네컷을 채웠어요` / 친구가 여럿이면 `민지님 외 1명이 네컷을 채웠어요` (가장 최근에 채운 친구, 이름 8자 넘으면 말줄임). 이름이 없거나 "친구"면 `친구가 네컷을 채웠어요` / `친구 2명이 네컷을 채웠어요`(#280)
  - 본문: `'제주 여행' 4칸 중 3칸이 찼어요. 1칸만 더 채우면 완성이에요!` (제목 없는 세션은 앞의 `'…' ` 를 뺀다)
- **한 행으로 모으기**: 대기 행의 `dedup_key` 를 `fill_progress:{세션}:{주인}:open` 으로 고정하고 `INSERT … ON DUPLICATE KEY UPDATE` 한 문장으로 넣는다. 친구 둘이 같은 순간 채워도 유니크 키가 한 행으로 모은다(롤백·데드락 없이). MySQL 전용 문장이라 이 경로의 테스트는 실제 MySQL(`FillProgressPushTest`).
- **지표**: 새 행은 `notification.requested{type=fill_progress}` 1, 합침은 `notification.collapsed{type}`(채움 n번 → 알림 1통의 n), 취소는 `notification.cancelled{type}`. 잃은 수 = 요청 − 보냄 − 만료 − 취소(아웃박스 행의 최종 결과, `notification.relay.processed` 기준, #280). 알려진 종류·결과의 카운터는 기동 때 0 으로 미리 만든다 — 처음 생긴 카운터의 첫 증가는 `increase()` 가 못 본다(#280).
- 설정: `notification.fill-progress.quiet-seconds`(300 — 처음 10분에서 실기기 테스트 뒤 5분으로, #280), `window-seconds`(3600).
- 진행 알림이 이미 나간 뒤 친구가 마지막 칸을 채우면 진행·완성 두 통이 된다(보내는 순간엔 곧 완성될지 알 수 없다). 대기 중에 완성되면 완성 한 통.

### 발송 전용 프로세스 — notifier 프로파일 (#274)

같은 jar 를 `--spring.profiles.active=prod,notifier` 로 띄우면 HTTP·WebSocket 없이 릴레이·스트림 소비자·배치만 돈다(`application-notifier.yml`: `web-application-type: none`). 웹 인스턴스에 `notification.worker.enabled=false` 를 주면 넣기(enqueue)만 한다 — 사건 트랜잭션의 아웃박스 행은 그대로라 어느 쪽이 보내든 잃지 않는다.

```bash
# 로컬 시연: 웹(넣기만) + 발송 전용(보내기만)
./gradlew bootRun --args='--notification.worker.enabled=false'
java -jar build/libs/server-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev,notifier
```

**지금은 운영에 따로 띄우지 않는다**(인프라 추가 없음, 서버마다 소비자 1). 떼어 내는 기준:
- 소비자 그룹 `lag`(아직 아무도 꺼내지 않은 수, `XINFO GROUPS notifications`)가 배치 뒤에도 몇 분 넘게 줄지 않는다
- 웹 인스턴스 CPU·DB 커넥션 사용에 발송 시간대(09:00 배치)가 보인다
- 발송량을 웹 트래픽과 따로 늘리고 줄여야 한다

떼어 낼 때: notifier 인스턴스(작은 것 1대, ALB 대상 아님)를 위 프로파일로 띄우고 → 웹 인스턴스에 `notification.worker.enabled=false` → 배포. 되돌리기는 그 한 줄을 지우면 된다.

### 왜 아웃박스인가

커밋 뒤 메모리 이벤트(#211 까지의 길)는 커밋과 발송 사이에 서버가 재시작되거나 FCM 이 거절하면 요청 자체가 사라졌다(#269: 서킷 열림으로 이틀간 18건). 큐에 바로 넣어도 "DB 커밋은 됐는데 큐에 넣기 전에 죽음"이 남는다(이중 쓰기). 요청을 DB 트랜잭션에 묶는 것이 유일하게 틈이 없는 자리다. 큐(Redis Streams, #273)는 이 위에 전달·소비자 분리 역할로 얹는다.

## 왜 이렇게

- **커서 = id**: IDENTITY 라 생성 순서와 같다. 오프셋 페이지는 보는 중 새 알림이 끼면 한 건이 두 번 보이거나 빠진다.
- **읽음을 시각으로**: "읽었나"에 더해 "보낸 뒤 얼마 만에"가 남아 도달·반응 시간을 셀 수 있다.
- **안 읽은 개수는 count 쿼리**: 사용자당 행이 수십 건이라 충분하다. 느려지면 Redis 캐시로 — 계기는 이 조회의 p95.
- **큐 없음**: 저장은 DB 트랜잭션이라 유실이 없다. 큐는 발송 유실 방지용이고 전달 방식 줄기에서 따로 간다.

## 관측

- **도착 지연** `notification.delivery.latency{type}`(#283): 아웃박스 행의 보낼 시각(`not_before`)부터 보냄(SENT)까지 — 일부러 미룬 시간(묶음 대기·예약)은 빠지고, 릴레이 대기(최대 3초)·큐·재시도·알림함 저장·FCM 호출은 든다. 버킷 13개(0.1초~2시간)로 정해 둔 히스토그램, 종류별로 기동 때 0 으로 등록. 대시보드는 1시간 창 p50·p95. 스트림 모드는 재시도해도 `not_before` 가 그대로라 재시도 지연까지 들고, 직접 모드(롤백용)는 재시도 때 `not_before` 를 옮겨 마지막 시도 뒤만 든다. 만료·취소·DEAD 는 기록하지 않는다(보내지 않았으니).
- `notification.recorded{type}` 저장 수, `notification.opened{type}` 처음 읽은 수. `notification.requested{type}` 요청 수와 `notification.record_failed{type}` 저장 실패 수는 #271(contracts/push.md 관측). `push.sent{type,result}` 와 함께 보면 "저장 → FCM 수락 → 열람"이 한 줄.
- 로그 `Notification cleanup batch end (deleted, retentionDays)`.

## 설정

```yaml
cleanup:
  notification:
    cron: "0 20 5 * * *"
    retention-days: 90
```

## DDL (ddl-auto 가 update 가 아닌 환경용)

```sql
CREATE TABLE notifications (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  type VARCHAR(32) NOT NULL,
  title VARCHAR(100) NOT NULL,
  body VARCHAR(300) NOT NULL,
  data VARCHAR(1000) NOT NULL,
  created_at DATETIME(6) NULL,
  read_at DATETIME(6) NULL,
  CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users(id),
  INDEX idx_notifications_user_id (user_id, id),
  INDEX idx_notifications_user_read (user_id, read_at),
  INDEX idx_notifications_created (created_at)
);
```

## 다음

종류가 늘면(공지 등) 같은 `NotificationOutboxService.enqueue` 길을 탄다(#272). 묶어야 하는 종류는 `enqueueCollapsed` + `OutboxMessageRenderer` 하나(#274 `fill_progress` 가 선례). 후보: 주인이 그 세션을 이미 열어 봤으면 진행 알림을 건너뛰기(열람 기록이 서버에 생기면). 만료 전날 안내(`fill_expiring`)가 첫 번째 추가다(#213). 알림 설정(종류별 끄기)은 `record` 앞에서 거른다.
