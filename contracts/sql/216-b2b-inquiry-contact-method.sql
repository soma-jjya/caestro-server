-- #216 후속: 연락 방법 선택(전화·이메일·인스타 중 하나). 검토용 MySQL DDL, 자동 실행 파일이 아니다.
-- 운영은 ddl-auto: update 라 새 컬럼 둘(contact_method·instagram)은 배포 때 Hibernate 가 추가한다.
-- 하지만 ddl-auto 는 기존 컬럼의 NOT NULL 을 풀지 않는다 → 1번은 반드시 손으로 한 번 실행해야
-- 이메일·인스타만 남긴 문의가 저장된다(안 하면 phone NULL 로 INSERT 가 실패해 500).
-- 전화로 남긴 문의는 1번 전에도 그대로 저장된다.

-- 1. (필수, 손으로) phone NULL 허용. 몇 번 실행해도 결과가 같다
ALTER TABLE b2b_inquiries MODIFY phone VARCHAR(20) NULL;

-- 2. (배포 전에 적용할 때만) 새 컬럼. 배포가 먼저 돌았다면 이미 있다 — 아래로 확인하고 없을 때만 실행
--    SELECT column_name FROM information_schema.columns
--     WHERE table_schema = DATABASE() AND table_name = 'b2b_inquiries' AND column_name IN ('contact_method', 'instagram');
ALTER TABLE b2b_inquiries
    ADD COLUMN contact_method VARCHAR(16) NULL AFTER contact_name,   -- PHONE | EMAIL | INSTAGRAM. NULL 인 옛 행은 서버가 PHONE 으로 읽는다
    ADD COLUMN instagram VARCHAR(30) NULL AFTER email;               -- @ 를 뗀 아이디

-- 3. (선택) 옛 행 채우기. 안 해도 서버는 NULL 을 PHONE 으로 읽는다 — SQL 로 방법별 개수를 셀 때 편하라고
UPDATE b2b_inquiries SET contact_method = 'PHONE' WHERE contact_method IS NULL;
