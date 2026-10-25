-- #174 검토용 MySQL DDL. 자동 실행 파일이 아니다.
-- 기존 스키마/ddl-auto 설정을 확인하고 한 번만 적용한다.
ALTER TABLE fill_sessions ADD COLUMN frame_locked BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE b2b_projects (
    id VARCHAR(40) NOT NULL PRIMARY KEY,
    name VARCHAR(60) NOT NULL,
    slug VARCHAR(40) NOT NULL,
    description VARCHAR(120) NOT NULL,
    status ENUM('DRAFT', 'LIVE', 'PAUSED') NOT NULL,
    published_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_b2b_project_slug UNIQUE (slug)
) ENGINE=InnoDB;

CREATE TABLE b2b_images (
    id VARCHAR(40) NOT NULL PRIMARY KEY,
    source_image_id VARCHAR(32) NOT NULL,
    content_type VARCHAR(40) NOT NULL,
    data LONGBLOB NOT NULL,
    width INT NOT NULL,
    height INT NOT NULL,
    created_at DATETIME(6) NOT NULL
) ENGINE=InnoDB;

CREATE TABLE b2b_frames (
    id VARCHAR(40) NOT NULL PRIMARY KEY,
    project_id VARCHAR(40) NOT NULL,
    name VARCHAR(30) NOT NULL,
    layout ENUM('GRID', 'STRIP') NOT NULL,
    image_id VARCHAR(40) NULL,
    windows_json TEXT NOT NULL,
    display_order INT NOT NULL,
    version INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_b2b_frame_project (project_id, display_order),
    CONSTRAINT fk_b2b_frame_project FOREIGN KEY (project_id) REFERENCES b2b_projects(id),
    CONSTRAINT fk_b2b_frame_image FOREIGN KEY (image_id) REFERENCES b2b_images(id)
) ENGINE=InnoDB;

CREATE TABLE b2b_fills (
    id VARCHAR(40) NOT NULL PRIMARY KEY,
    session_id BIGINT NOT NULL UNIQUE,
    project_id VARCHAR(40) NOT NULL,
    image_id VARCHAR(40) NOT NULL,
    frame_json TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_b2b_fill_session FOREIGN KEY (session_id) REFERENCES fill_sessions(id) ON DELETE CASCADE,
    CONSTRAINT fk_b2b_fill_project FOREIGN KEY (project_id) REFERENCES b2b_projects(id),
    CONSTRAINT fk_b2b_fill_image FOREIGN KEY (image_id) REFERENCES b2b_images(id)
) ENGINE=InnoDB;
