-- #272 검토용 MySQL DDL. 자동 실행 파일이 아니다(운영은 ddl-auto update 가 만든다).
CREATE TABLE notification_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    type VARCHAR(40) NOT NULL,
    title VARCHAR(100) NOT NULL,
    body VARCHAR(500) NOT NULL,
    data VARCHAR(1000) NOT NULL,               -- 푸시 data JSON
    dedup_key VARCHAR(120) NOT NULL,           -- 같은 알림 두 번 금지
    collapse_key VARCHAR(120) NULL,            -- 묶음 알림(#274)
    not_before DATETIME(6) NOT NULL,           -- 이 시각 전엔 보내지 않음(재시도 백오프)
    expires_at DATETIME(6) NULL,               -- 지나면 EXPIRED
    status VARCHAR(16) NOT NULL,               -- PENDING | PROCESSING | RELAYED(#273) | SENT | EXPIRED | DEAD
    attempts INT NOT NULL,
    lease_until DATETIME(6) NULL,
    claim_token VARCHAR(36) NULL,
    notification_id BIGINT NULL,               -- 알림함 행(한 번만 만든다)
    last_error VARCHAR(300) NULL,
    created_at DATETIME(6) NULL,
    sent_at DATETIME(6) NULL,
    CONSTRAINT uk_notification_outbox_dedup UNIQUE (dedup_key),
    INDEX idx_notification_outbox_due (status, not_before, id),
    INDEX idx_notification_outbox_collapse (collapse_key, status),
    INDEX idx_notification_outbox_sent (status, sent_at)
);
