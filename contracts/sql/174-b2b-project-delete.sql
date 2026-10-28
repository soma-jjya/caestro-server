-- 프로젝트 삭제 API 배포 전에 운영 DB에 적용한다. 기존 테이블/행은 변경하지 않는다.
-- DB 삭제와 외부 저장소 정리를 분리하는 영속 작업 목록이며 FK는 의도적으로 없다.
CREATE TABLE IF NOT EXISTS b2b_asset_deletions (
    id VARCHAR(80) NOT NULL,
    kind ENUM('FILL_SESSION', 'FRAME_IMAGE') NOT NULL,
    target_id VARCHAR(40) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_b2b_deletion_due (next_attempt_at, id)
) ENGINE=InnoDB;

-- 정리 완료 확인 (0행이어야 해당 시점의 대기 작업이 없음).
-- SELECT id, kind, target_id, created_at, next_attempt_at FROM b2b_asset_deletions ORDER BY next_attempt_at, id;
