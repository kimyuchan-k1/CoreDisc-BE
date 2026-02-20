-- k6 부하 테스트용 시드 데이터
-- 사용법: mysql -u root -pkim980618 coredisc < k6/seed.sql

-- 1. 약관 데이터 (필수 3개 + 선택 3개)
INSERT IGNORE INTO terms (id, type, content, version, is_required, created_at, updated_at)
VALUES
    (1, 'SERVICE_USE_REQUIRED', '서비스 이용약관 내용입니다.', 100, true, NOW(), NOW()),
    (2, 'PRIVACY_REQUIRED', '개인정보 수집 및 이용 동의 내용입니다.', 100, true, NOW(), NOW()),
    (3, 'AGE_OVER_14', '만 14세 이상 여부 확인 내용입니다.', 100, true, NOW(), NOW()),
    (4, 'MARKETING_AGREEMENT', '마케팅 활용 및 광고 수신 동의 내용입니다.', 100, false, NOW(), NOW()),
    (5, 'THIRD_PARTY_PROVIDE', '개인정보 제3자 제공 동의 내용입니다.', 100, false, NOW(), NOW()),
    (6, 'OUTSOURCING_AGREEMENT', '개인정보 처리 위탁 동의 내용입니다.', 100, false, NOW(), NOW());

-- 2. 기본 프로필 이미지 (회원가입 시 ID=1 필요)
INSERT IGNORE INTO profile_img (id, img_url, member_id, created_at, updated_at)
VALUES (1, 'https://local-stub/default-profile.png', NULL, NOW(), NOW());

SELECT 'Seed data inserted successfully' AS status;
