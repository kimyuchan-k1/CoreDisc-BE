# 01. 부하 테스트 환경 구축 트러블슈팅

## 배경

k6를 이용한 부하 테스트 환경을 구축하는 과정에서 발생한 여러 이슈들을 기록합니다.

---

## 이슈 1: 외부 의존성으로 로컬 실행 불가

### 문제
앱이 FCM, S3, SMTP 등 외부 서비스에 의존하여 로컬에서 부하 테스트 실행이 불가능했습니다.

### 해결
`@Profile` 기반 Stub 패턴을 적용하여 외부 의존성을 분리했습니다.

| 서비스 | 인터페이스 | 실제 구현 (@Profile("!local")) | Stub (@Profile("local")) |
|--------|-----------|-------------------------------|--------------------------|
| FCM | `FcmService` | `FcmServiceImpl` | `FcmServiceStub` |
| S3 | `ImageStorageService` | `AmazonS3Manager` | `LocalImageStorageStub` |
| Mail | `MailService` | `MailServiceImpl` | `MailServiceStub` |

Config 클래스에도 `@Profile("!local")` 추가: `FirebaseConfig`, `S3Config`, `MailConfig`

---

## 이슈 2: 회원가입 시 다양한 Validation 에러

### 문제들

**2-1. Username 16자 제한 초과**
```
k6smoke_1708123456789  →  21자 (한도: 16자)
```
- **해결**: prefix를 `k6s_`로 줄이고 timestamp를 8자로 제한

**2-2. Name 필드 숫자 포함 불가**
```
정규식: ^[a-zA-Z가-힣]{1,16}$
입력값: 테스트1  →  숫자 '1' 포함되어 거부
```
- **해결**: name을 `'TestUser'`로 고정

**2-3. Password 특수문자 JSON 파싱 이슈**
```
testpass123!  →  '!' 문자가 shell/JSON에서 문제 발생
```
- **해결**: `testpass123a`로 변경 (영문+숫자 조합으로 충분)

---

## 이슈 3: 기본 프로필 이미지 부재

### 문제
회원가입 시 `profile_img` 테이블의 id=1 레코드가 필요하지만 존재하지 않아 500 에러 발생.

### 해결
`k6/seed.sql`에 기본 프로필 이미지 INSERT 추가:
```sql
INSERT IGNORE INTO profile_img (id, img_url, member_id, created_at, updated_at)
VALUES (1, 'https://local-stub/default-profile.png', NULL, NOW(), NOW());
```

---

## 이슈 4: 빈 DB에서 비현실적인 응답속도

### 문제
데이터가 없는 상태에서 부하 테스트를 실행하니 p95 응답시간이 ~11ms로 나와 실제 운영 환경과 괴리가 컸습니다.

### 해결
Python 스크립트(`k6/generate_seed_data.py`)로 대량 테스트 데이터를 생성했습니다.

| 테이블 | 레코드 수 |
|--------|----------|
| member | 10,000 |
| post | 200,000 |
| post_answer | 800,000 |
| comment | 500,000 |
| notification | 1,000,000 |
| follow | 200,000 |
| post_like | 300,000 |
| official_question | 50,000 |
| personal_question | 30,000 |
| disc | 100,000 |

- SQL 파일 크기: 724MB
- DB 적재 후 크기: **918.4MB**
- 적재 소요 시간: ~64초

---

## 이슈 5: k6 테스트 스크립트의 API 파라미터 오류

### 문제들

**5-1. Questions Basic 파라미터명 오류**
```
잘못된 요청: GET /api/questions/basic?category=SERVICE_USE_REQUIRED
올바른 요청: GET /api/questions/basic?categoryId=1
```

**5-2. Shell에서 URL의 & 문자 처리**
```bash
# zsh에서 & 가 백그라운드 실행으로 해석됨
curl http://localhost:8080/api/posts?feedType=ALL&size=10  # 실패
curl "http://localhost:8080/api/posts?feedType=ALL&size=10"  # 성공
```

### 해결
- 파라미터명을 API 스펙에 맞게 수정
- k6 스크립트에서는 template literal 사용으로 문제 없음

---

## 최종 테스트 설정

### k6 시나리오 구성
```
load_test (ramping-vus):
  0s~30s   : 0 → 10 VUs   (warm-up)
  30s~1m30s: 10 → 50 VUs  (load)
  1m30s~2m : 50 → 100 VUs (peak)
  2m~3m    : 100 VUs      (sustain peak)
  3m~3m30s : 100 → 0 VUs  (cool-down)
```

### 시나리오 가중치
| 시나리오 | 가중치 | 테스트 엔드포인트 |
|----------|--------|------------------|
| Feed | 35% | ALL/CORE 피드, 커서 페이지네이션 |
| Profile | 20% | 내 프로필, 내 포스트, 다른 유저 프로필, 팔로워/팔로잉 |
| Notifications | 20% | 안읽은 알림, 알림 목록, 리마인더 설정 |
| Questions | 15% | 카테고리별 질문, 인기 질문, 선택 질문, 카테고리 목록, 검색 |
| Search | 10% | 멤버 검색, 검색 기록 |

### 임계값
```
http_req_duration  p(95) < 2000ms
error_rate         < 10%
feed_duration      p(95) < 1500ms
notification_duration p(95) < 1500ms
search_duration    p(95) < 1000ms
```
