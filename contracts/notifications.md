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
6. 보관: 알림함 행은 `cleanup.notification`(기본 90일), 아웃박스는 `cleanup.outbox`(SENT·EXPIRED 7일, DEAD 30일).

### 왜 아웃박스인가

커밋 뒤 메모리 이벤트(#211 까지의 길)는 커밋과 발송 사이에 서버가 재시작되거나 FCM 이 거절하면 요청 자체가 사라졌다(#269: 서킷 열림으로 이틀간 18건). 큐에 바로 넣어도 "DB 커밋은 됐는데 큐에 넣기 전에 죽음"이 남는다(이중 쓰기). 요청을 DB 트랜잭션에 묶는 것이 유일하게 틈이 없는 자리다. 큐(Redis Streams, #273)는 이 위에 전달·소비자 분리 역할로 얹는다.

## 왜 이렇게

- **커서 = id**: IDENTITY 라 생성 순서와 같다. 오프셋 페이지는 보는 중 새 알림이 끼면 한 건이 두 번 보이거나 빠진다.
- **읽음을 시각으로**: "읽었나"에 더해 "보낸 뒤 얼마 만에"가 남아 도달·반응 시간을 셀 수 있다.
- **안 읽은 개수는 count 쿼리**: 사용자당 행이 수십 건이라 충분하다. 느려지면 Redis 캐시로 — 계기는 이 조회의 p95.
- **큐 없음**: 저장은 DB 트랜잭션이라 유실이 없다. 큐는 발송 유실 방지용이고 전달 방식 줄기에서 따로 간다.

## 관측

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

종류가 늘면(친구가 칸 채움, 공지) 같은 `NotificationOutboxService.enqueue` 길을 탄다(#272). 만료 전날 안내(`fill_expiring`)가 첫 번째 추가다(#213). 알림 설정(종류별 끄기)은 `record` 앞에서 거른다.
