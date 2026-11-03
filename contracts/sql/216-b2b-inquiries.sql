-- #216 검토용 MySQL DDL. 자동 실행 파일이 아니다.
-- 기존 스키마/ddl-auto 설정을 확인하고 한 번만 적용한다.
CREATE TABLE b2b_inquiries (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    type VARCHAR(16) NOT NULL,                 -- CAFE | FESTIVAL | TOURISM | OTHER
    organization VARCHAR(80) NOT NULL,
    contact_name VARCHAR(40) NOT NULL,
    phone VARCHAR(20) NOT NULL,
    email VARCHAR(120) NULL,
    region VARCHAR(40) NULL,
    preferred_period VARCHAR(80) NULL,
    scale VARCHAR(80) NULL,
    website VARCHAR(200) NULL,
    message VARCHAR(500) NULL,
    privacy_agreed BIT(1) NOT NULL,
    marketing_agreed BIT(1) NOT NULL,
    utm_source VARCHAR(100) NULL,
    utm_medium VARCHAR(100) NULL,
    utm_campaign VARCHAR(100) NULL,
    utm_content VARCHAR(100) NULL,
    utm_term VARCHAR(100) NULL,
    landing_path VARCHAR(200) NULL,
    client_ip VARCHAR(45) NULL,
    user_agent VARCHAR(300) NULL,
    status VARCHAR(16) NOT NULL,               -- NEW | CONTACTED | PROPOSAL_SENT | WON | LOST | SPAM
    admin_memo VARCHAR(2000) NULL,
    linked_project_id VARCHAR(40) NULL,        -- b2b_projects.id (FK 없음: 프로젝트를 지워도 문의 기록은 남는다)
    handled_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NULL,
    INDEX idx_b2b_inquiries_status (status, id),
    INDEX idx_b2b_inquiries_type (type, id),
    INDEX idx_b2b_inquiries_created (created_at)
) ENGINE=InnoDB;
