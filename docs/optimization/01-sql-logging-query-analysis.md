# 01. SQL 로깅 & API별 쿼리 수 분석 (Phase 0-1)

## 설정

### p6spy 의존성 추가
```gradle
// build.gradle
implementation 'com.github.gavlyukovskiy:p6spy-spring-boot-starter:1.9.0'
```

### application-local.yml 설정
```yaml
spring:
  jpa:
    properties:
      hibernate:
        format_sql: true
        use_sql_comments: true

logging:
  level:
    org.hibernate.SQL: DEBUG
    org.hibernate.orm.jdbc.bind: TRACE

decorator:
  datasource:
    p6spy:
      enable-logging: true
      logging: slf4j
      multiline: true
```

---

## API별 쿼리 수 측정 결과

> 측정 방법: p6spy `| statement |` 라인 카운트 (정확한 실행 쿼리 수)
> 측정 환경: 918MB 시드 데이터, local 프로필

### 결과표

| API | 쿼리 수 | 심각도 | N+1 여부 |
|-----|---------|--------|---------|
| **GET /api/posts?feedType=ALL&size=10** | **16** | 높음 | O (질문 N+1) |
| **GET /api/posts?feedType=CORE&size=10** | **17** | 높음 | O (질문 N+1) |
| GET /api/members/my-home | 8 | 중간 | 확인 필요 |
| GET /api/members/my-home/posts | 6 | 낮음 | - |
| GET /api/members/my-home/{username} | 13 | 중간 | O |
| GET /api/notifications/unread | 5 | 낮음 | - |
| **GET /api/notifications?size=10** | **16** | 높음 | O |
| **GET /api/questions/basic?categoryId=1** | **20** | 높음 | O |
| GET /api/questions/popular | 9 | 중간 | 확인 필요 |
| GET /api/questions/selected | 8 | 중간 | 확인 필요 |
| GET /api/questions/categories | 5 | 낮음 | - |
| **GET /api/search/members?keyword=load** | **16** | 높음 | O |
| **GET /api/followers** | **26** | 심각 | O (가장 심각) |
| GET /api/followings | 16 | 높음 | O |

### 심각도 기준
- 낮음: 1~6 쿼리 (정상)
- 중간: 7~10 쿼리 (개선 가능)
- 높음: 11~20 쿼리 (N+1 의심)
- 심각: 21+ 쿼리 (즉시 수정 필요)

---

## N+1 분석

### 1. 피드 조회 (16~17 쿼리)

예상 구조:
```
1. 멤버 조회 (Security Context)
2. 프로필 이미지 조회
3. 게시글 목록 조회 (fetchJoin: member, profileImg)
4. 답변 배치 조회 (IN절)
5~14. TodayQuestion 개별 조회 × 10건 ← N+1!
15. 추가 쿼리들
```

**최적화 목표**: 16쿼리 → 3~4쿼리

### 2. 팔로워 목록 (26 쿼리 — 가장 심각)

예상 구조:
```
1. 멤버 조회
2. 팔로워 목록 조회
3~26. 각 팔로워의 멤버/프로필 정보 개별 조회 × 20+ ← N+1!
```

**최적화 목표**: 26쿼리 → 1~2쿼리

### 3. 질문 목록 (20 쿼리)

예상 구조:
```
1. 멤버 조회
2. 카테고리별 질문 목록 조회
3~20. 각 질문의 연관 엔티티 개별 로딩 ← N+1!
```

**최적화 목표**: 20쿼리 → 2~3쿼리

### 4. 알림 목록 (16 쿼리)

예상 구조:
```
1. 멤버 조회
2. 알림 목록 조회
3~16. 각 알림의 sender/receiver 멤버 개별 조회 ← N+1!
```

**최적화 목표**: 16쿼리 → 2~3쿼리

---

## 최적화 우선순위 (쿼리 수 × 호출 빈도)

| 순위 | API | 쿼리 수 | 호출 빈도 | 우선순위 |
|------|-----|---------|----------|---------|
| 1 | 피드 조회 (ALL/CORE) | 16~17 | 매우 높음 | **즉시** |
| 2 | 팔로워 목록 | 26 | 중간 | **즉시** |
| 3 | 알림 목록 | 16 | 높음 | 높음 |
| 4 | 질문 목록 (basic) | 20 | 중간 | 높음 |
| 5 | 검색 | 16 | 중간 | 중간 |
| 6 | 팔로잉 목록 | 16 | 중간 | 중간 |

---

## 다음 단계

이 측정 결과를 기반으로 Phase 1 (인덱스 + 트랜잭션)과 Phase 2 (N+1 해결)에서 순차적으로 최적화합니다.
