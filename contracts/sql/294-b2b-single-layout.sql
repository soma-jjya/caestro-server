-- #294 B2B 1×1(한 컷) 배치 SINGLE. 검토용 MySQL DDL, 자동 실행 파일이 아니다.
-- b2b_frames.layout 은 MySQL ENUM 이다(174-b2b.sql. Hibernate 가 만든 환경도 @Enumerated(STRING) 을 ENUM 으로 만든다).
-- 운영의 ddl-auto: update 는 이미 있는 열의 ENUM 값 목록을 늘리지 않는다 → 1번을 손으로 한 번 실행해야 SINGLE 프레임이 저장된다
-- (안 하면 SINGLE 프레임 저장이 "Data truncated for column 'layout'" 로 실패해 500).
-- 값만 늘리는 변경이라 기존 STRIP·GRID 행과 지금 떠 있는 서버(SINGLE 을 모름)는 그대로 동작한다 → 서버 배포 **전에** 실행한다.
-- fill_sessions.layout 은 VARCHAR(20) 이라 'Single' 에 DDL 이 필요 없다.

-- 0. 지금 값 확인. ENUM 값 순서는 환경마다 다를 수 있으나, MODIFY 는 값을 문자열로 옮기므로 순서가 달라도 행 값은 그대로다
SHOW COLUMNS FROM b2b_frames LIKE 'layout';

-- 1. (필수, 손으로) SINGLE 추가. 몇 번 실행해도 결과가 같다. 값 순서가 바뀌면 표를 복사해 다시 쓰지만 b2b_frames 는 프로젝트당 10행 이하라 바로 끝난다
ALTER TABLE b2b_frames MODIFY layout ENUM('GRID', 'SINGLE', 'STRIP') NOT NULL;

-- 2. 확인: Type 이 enum('GRID','SINGLE','STRIP')
SHOW COLUMNS FROM b2b_frames LIKE 'layout';
