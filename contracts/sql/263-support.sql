-- #263 검토용 MySQL DDL. 자동 실행 파일이 아니다(운영은 ddl-auto update 가 만든다).
-- 기존 스키마/ddl-auto 설정을 확인하고 한 번만 적용한다.
CREATE TABLE support_tickets (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    category VARCHAR(16) NOT NULL,             -- BUG | ACCOUNT | PAYMENT | SUGGESTION | OTHER
    title VARCHAR(80) NOT NULL,
    status VARCHAR(16) NOT NULL,               -- OPEN | ANSWERED | CLOSED
    app_version VARCHAR(30) NULL,
    platform VARCHAR(10) NULL,
    os_version VARCHAR(40) NULL,
    device_model VARCHAR(60) NULL,
    user_unread BIT(1) NOT NULL,
    first_answered_at DATETIME(6) NULL,
    closed_at DATETIME(6) NULL,
    last_message_at DATETIME(6) NOT NULL,
    admin_memo VARCHAR(2000) NULL,
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    CONSTRAINT fk_support_tickets_user FOREIGN KEY (user_id) REFERENCES users (id),
    INDEX idx_support_tickets_user (user_id, id),
    INDEX idx_support_tickets_status (status, id),
    INDEX idx_support_tickets_last_message (last_message_at)
);

CREATE TABLE support_messages (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    ticket_id BIGINT NOT NULL,
    author VARCHAR(8) NOT NULL,                -- USER | ADMIN
    body VARCHAR(2000) NOT NULL,
    created_at DATETIME(6) NULL,
    CONSTRAINT fk_support_messages_ticket FOREIGN KEY (ticket_id) REFERENCES support_tickets (id),
    INDEX idx_support_messages_ticket (ticket_id, id)
);

CREATE TABLE support_attachments (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    ticket_id BIGINT NOT NULL,
    store_id VARCHAR(32) NOT NULL,             -- 저장소 키(S3 객체 키의 마지막 조각 / support_attachment_blobs.id)
    content_type VARCHAR(40) NOT NULL,
    size_bytes INT NOT NULL,
    created_at DATETIME(6) NULL,
    CONSTRAINT fk_support_attachments_ticket FOREIGN KEY (ticket_id) REFERENCES support_tickets (id),
    INDEX idx_support_attachments_ticket (ticket_id, id)
);

-- 로컬·CI(support.attachment-store=rds) 전용. 운영(s3)에서는 비어 있다
CREATE TABLE support_attachment_blobs (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    ticket_id BIGINT NOT NULL,
    content_type VARCHAR(40) NOT NULL,
    data LONGBLOB NOT NULL,
    size_bytes INT NOT NULL,
    created_at DATETIME(6) NULL,
    INDEX idx_support_attachment_blobs_ticket (ticket_id)
);
