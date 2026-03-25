# 02. TodayQuestion Non-Unique Query 에러 수정

## 증상

`GET /api/posts?feedType=CORE&size=10` 호출 시 500 에러 발생:

```
"Query did not return a unique result: 29 results were returned"
```

에러율: 44.28% → 10.92% (CORE 피드 제외 후 7.75%)

---

## 원인 분석

### 발생 경로

```
PostController.getPosts()
  → PostQueryServiceImpl.findPostFeed()
    → QueryPostRepositoryImpl.findPostFeed()
      → 각 포스트에 대해 질문 내용 조회
        → todayQuestionRepository.findByMemberAndQuestionOrderAndSelectedDateBetween()
          → ❌ NonUniqueResultException (29건 반환)
```

### 근본 원인

`JpaTodayQuestionRepository`의 Spring Data JPA 메서드:

```java
// 반환 타입이 Optional → JPA가 내부적으로 getSingleResult() 호출
// 결과가 2건 이상이면 NonUniqueResultException 발생
Optional<TodayQuestion> findByMemberAndQuestionOrderAndSelectedDateBetween(
    Member member, Integer questionOrder,
    LocalDate startDate, LocalDate endDate
);
```

**문제점:**
1. `(member, questionOrder, selectedDate)` 조합에 **UNIQUE 제약조건 없음**
2. `Between` 쿼리로 월 단위 범위 검색 시, 같은 월에 동일 member/questionOrder로 여러 레코드 존재 가능
3. Spring Data JPA의 `findBy...`는 `Optional` 반환 시 `fetchOne()` 호출 → 2건 이상이면 예외

### 추가 발견: 안전하지 않은 `.get()` 호출

`PostQueryServiceImpl.findQuestionContent()` (line 144, 147):

```java
// Before: Optional.get()을 isPresent() 확인 없이 호출
// → 데이터가 없으면 NoSuchElementException 발생
questions.add(todayQuestionRepository
    .findByMemberAndQuestionOrderAndSelectedDateBetween(member, i, startOfMonth, endOfMonth)
    .get());  // ❌ 위험
```

### 추가 발견: 아키텍처 위반

`NotificationReminderScheduler`가 도메인 인터페이스(`TodayQuestionRepository`)가 아닌 인프라 구현체(`JpaTodayQuestionRepository`)를 직접 주입받고 있었습니다.

```java
// Before: 인프라 레이어 직접 참조 (아키텍처 위반)
private final JpaTodayQuestionRepository todayQuestionRepository;

// After: 도메인 인터페이스 참조
private final TodayQuestionRepository todayQuestionRepository;
```

---

## 수정 내용

### 1. JPA Repository: `findBy` → `findFirstBy`

**파일**: `JpaTodayQuestionRepository.java`

```java
// Before
Optional<TodayQuestion> findByMemberAndQuestionOrderAndSelectedDateBetween(...);
Optional<TodayQuestion> findByMemberAndQuestionOrderAndSelectedDate(...);

// After: findFirst 접두사 추가 → Spring Data가 LIMIT 1 적용
Optional<TodayQuestion> findFirstByMemberAndQuestionOrderAndSelectedDateBetween(...);
Optional<TodayQuestion> findFirstByMemberAndQuestionOrderAndSelectedDate(...);
```

`findFirst` 접두사를 사용하면 Spring Data JPA가 `LIMIT 1`을 쿼리에 추가하여, 결과가 여러 건이어도 첫 번째만 반환합니다.

### 2. Adapter 호출부 업데이트

**파일**: `TodayQuestionRepositoryAdapter.java`

```java
// JPA Repository의 변경된 메서드명으로 호출 업데이트
return jpaTodayQuestionRepository.findFirstByMemberAndQuestionOrderAndSelectedDateBetween(...);
return jpaTodayQuestionRepository.findFirstByMemberAndQuestionOrderAndSelectedDate(...);
```

도메인 인터페이스(`TodayQuestionRepository`)의 메서드명은 변경하지 않아, 서비스 레이어 호출부는 수정 불필요.

### 3. 안전하지 않은 `.get()` 제거

**파일**: `PostQueryServiceImpl.java`

```java
// Before: NoSuchElementException 위험
questions.add(todayQuestionRepository
    .findByMemberAndQuestionOrderAndSelectedDateBetween(member, i, startOfMonth, endOfMonth)
    .get());

// After: 값이 있을 때만 추가
todayQuestionRepository
    .findByMemberAndQuestionOrderAndSelectedDateBetween(member, i, startOfMonth, endOfMonth)
    .ifPresent(questions::add);
```

### 4. 아키텍처 위반 수정

**파일**: `NotificationReminderScheduler.java`

```java
// Before
import com.coredisc.infrastructure.repository.question.JpaTodayQuestionRepository;
private final JpaTodayQuestionRepository todayQuestionRepository;

// After
import com.coredisc.domain.todayQuestion.TodayQuestionRepository;
private final TodayQuestionRepository todayQuestionRepository;
```

---

## 변경 파일 요약

| 파일 | 변경 내용 |
|------|----------|
| `JpaTodayQuestionRepository.java` | `findBy` → `findFirstBy` (2개 메서드) |
| `TodayQuestionRepositoryAdapter.java` | 호출부 메서드명 업데이트 |
| `PostQueryServiceImpl.java` | `.get()` → `.ifPresent()` |
| `NotificationReminderScheduler.java` | `JpaTodayQuestionRepository` → `TodayQuestionRepository` |

---

## 수정 결과

| 지표 | 수정 전 | 수정 후 |
|------|---------|---------|
| 에러율 | 7.75% (CORE 피드 제외) | **0.01%** |
| 피드 체크 성공률 | 77.7% | **100%** |
| CORE 피드 | 500 에러 (사용 불가) | **정상 동작** |
| 전체 체크 성공률 | 77.77% | **100%** |

---

## 교훈

1. **Spring Data JPA의 `Optional` 반환은 단일 결과를 보장하지 않는다** — 데이터에 UNIQUE 제약이 없으면 `findFirstBy`를 사용해야 안전
2. **`.get()` 직접 호출은 항상 위험** — `ifPresent()`, `orElse()`, `orElseThrow()`를 사용
3. **아키텍처 레이어 경계를 준수** — 스케줄러(application layer)에서 JPA Repository(infrastructure layer) 직접 참조 금지
4. **대량 데이터로 테스트해야 실제 버그가 드러난다** — 빈 DB에서는 발견되지 않는 이슈
