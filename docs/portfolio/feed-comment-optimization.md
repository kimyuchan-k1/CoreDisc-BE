# CoreDisc 백엔드 성능 최적화 — 피드 & 댓글 API

> Spring Boot + JPA + MySQL + Redis 기반 일기/소셜 서비스의 API 응답 속도를 체계적으로 개선한 과정

---

## 1. 프로젝트 개요

**CoreDisc**는 사용자가 매일 질문에 답하며 일기를 작성하고, 팔로우 기반 피드에서 서로의 글을 공유하는 소셜 서비스다. Spring Boot + JPA + MySQL + Redis 스택으로 구축되었으며, FCM을 통한 푸시 알림, 서클(친한 친구) 필터링, 커서 기반 페이지네이션 등의 기능을 포함한다.

이 문서는 **피드 조회**, **댓글 조회**, **좋아요/댓글 알림** 세 가지 핵심 API의 성능을 4단계에 걸쳐 최적화한 전체 과정을 기록한다.

**기술 스택**: Java 17, Spring Boot 3.x, JPA/Hibernate, QueryDSL, MySQL 8.0, Redis, FCM, k6(부하 테스트), p6spy(쿼리 분석)

---

## 2. 성능 최적화를 시작한 이유

### 문제 인식

서비스 출시를 앞두고 하나의 질문이 있었다. "현재 코드가 실제 사용자 트래픽을 견딜 수 있는가?"

개발 단계에서는 빈 데이터베이스 또는 소량의 테스트 데이터로만 동작을 확인했기 때문에, 실 서비스 규모의 데이터에서 어떤 병목이 발생하는지 전혀 파악되지 않은 상태였다. 특히 피드 조회는 사용자가 앱을 열 때마다 호출되는 가장 빈도 높은 API이므로, 이 부분의 성능이 곧 서비스 전체의 체감 품질을 결정한다.

### 접근 방식: "측정 먼저, 최적화는 그 다음"

감으로 최적화하는 것이 아니라, 데이터 기반으로 의사결정하기 위해 다음 순서를 설계했다.

1. **측정 환경 구축** — 918MB 시드 데이터 생성, k6 부하 테스트 스크립트 작성, p6spy SQL 로깅
2. **Baseline 측정** — 100 VUs 동시 접속, 3분 30초 부하 테스트로 현재 상태 수치화
3. **병목 지점 식별** — EXPLAIN 분석, API별 쿼리 수 측정
4. **단계적 최적화** — Phase 1(인덱스/트랜잭션) -> Phase 2(N+1 쿼리) -> Phase 3(비동기 전환) -> Phase 4(캐싱)
5. **각 단계 검증** — 동일 조건 부하 테스트로 개선 효과 정량 측정

### Baseline 측정 결과

918MB 데이터(유저 10,000명, 게시글/댓글/팔로우 대량 데이터), 100 VUs 동시 접속 기준:

| 지표 | 측정값 |
|------|--------|
| http_req_duration p95 | **518ms** |
| Feed p95 | 489ms |
| 처리량 | 86 req/s |
| 총 요청 (3분 30초) | 20,269건 |

p95 518ms는 "20명 중 1명은 0.5초 이상 기다린다"는 의미다. 모바일 환경에서 이 수치는 사용자가 체감하는 지연으로 이어진다. 목표는 p95 300ms 이하로 설정했다.

---

## 3. Phase 1: 인덱스 + 트랜잭션 최적화

### 3.1 문제 발견 과정

성능 병목의 첫 번째 용의자는 데이터베이스였다. EXPLAIN으로 주요 쿼리의 실행 계획을 분석한 결과, 심각한 비효율이 드러났다.

**TodayQuestion 조회 (가장 심각)**
```
type: ref | rows: 212 | filtered: 1.11%
```
`member_id` FK 인덱스만 존재했기 때문에, 특정 멤버의 TodayQuestion을 찾기 위해 **212행을 스캔한 뒤 그중 1.11%만 사용**하고 있었다. 피드에서 게시글 10건을 조회하면 이 쿼리가 10번 반복되므로, 실제로는 **2,120행의 불필요한 스캔**이 발생하는 셈이다.

**댓글 조회**
```
type: ref | rows: 3 | filtered: 10%
```
`post_id` FK 인덱스만 사용하고, `depth=0`(부모 댓글) 필터링은 메모리에서 수행. 데이터가 늘어나면 비효율이 가중되는 구조였다.

**Follow 서클 조회**
```
type: ref | rows: 20 | filtered: 50%
```
`follower_id` 인덱스로 20행을 가져온 뒤, `is_circle` 필터링으로 절반을 버리고 있었다.

공통적인 문제 패턴은 **"FK 자동 인덱스에만 의존하고, 비즈니스 쿼리에 맞는 복합 인덱스가 전혀 없다"**는 것이었다.

### 3.2 해결 과정: 복합 인덱스 설계

인덱스를 추가할 때 핵심은 **"WHERE 절의 등치 조건 컬럼을 앞에, 범위 조건 컬럼을 뒤에"** 배치하는 것이다. 이 원칙에 따라 각 쿼리의 실행 패턴을 분석하고 인덱스를 설계했다.

**TodayQuestion**: `(member_id, question_order, selected_date)`
- `member_id`와 `question_order`는 등치 조건, `selected_date`는 BETWEEN 범위 조건
- 이 순서로 배치해야 B-Tree에서 등치 조건으로 빠르게 좁힌 뒤, 범위 조건으로 최종 필터링

**Comment**: `(post_id, depth, id)`
- `post_id`와 `depth`는 등치 조건, `id`는 커서 기반 페이지네이션의 정렬/범위 조건
- `id`를 인덱스에 포함시켜 커버링 인덱스 효과를 노림

**Follow**: `(follower_id, is_circle)`
- 서클 피드 필터링에서 `follower_id = ?` AND `is_circle = true` 조건을 인덱스만으로 해결

총 **5개 테이블에 7개 복합 인덱스**를 JPA `@Table(indexes = {...})` 어노테이션으로 추가했다.

### 3.3 추가 최적화: 트랜잭션 + 배치 페치 + 커넥션 풀

인덱스와 함께 세 가지를 병행했다.

**@Transactional(readOnly = true)**
`PostQueryServiceImpl`에 누락되어 있던 읽기 전용 트랜잭션 설정을 추가했다. 이를 통해 Hibernate dirty checking이 비활성화되어 메모리/CPU를 절약하고, 향후 Read Replica 라우팅의 기반을 마련했다.

**Hibernate batch_fetch_size: 100**
Lazy loading 발생 시 개별 SELECT 대신 `IN(id1, id2, ..., id100)` 배치 조회로 전환. N+1 문제의 영향을 구조적으로 완화하는 안전망 역할이다.

**HikariCP 커넥션 풀 튜닝**
`maximum-pool-size`를 10에서 20으로 늘리고, `connection-timeout`을 30초에서 5초로 줄여 빠른 실패를 유도했다. 100 VUs 동시 접속 환경에서 커넥션 대기로 인한 지연을 방지하기 위한 설정이다.

### 3.4 검증 결과

**EXPLAIN 개선**

| 쿼리 | Before | After |
|------|--------|-------|
| TodayQuestion | rows:212, filtered:1.11% | **rows:1, filtered:100%** |
| Post 피드 | rows:20, filtered:50% | **rows:100, filtered:100%** |
| Comment 댓글 | rows:3, filtered:10% | **rows:1, filtered:100%** |
| Follow 서클 | rows:20, filtered:50% | **rows:2, filtered:100%** |

TodayQuestion의 스캔 행수가 212에서 1로 줄었다는 것은, 인덱스가 정확히 필요한 행만 찾아가고 있다는 의미다.

**부하 테스트 결과**

| 지표 | Baseline | Phase 1 | 개선율 |
|------|----------|---------|--------|
| http_req_duration p95 | 518ms | **266ms** | **-48.6%** |
| Feed p95 | 489ms | **245ms** | **-49.9%** |
| Profile p95 | 438ms | **130ms** | **-70.3%** |
| 처리량 | 86 req/s | **93 req/s** | **+8.1%** |
| 총 요청 | 20,269 | **22,075** | **+8.9%** |

복합 인덱스 추가만으로 p95가 절반 가까이 줄었다. 이 결과는 **"인덱스가 없는 상태에서 대량 데이터를 다루면, 아무리 코드가 깨끗해도 성능은 보장되지 않는다"**는 점을 명확히 보여준다.

---

## 4. Phase 2: N+1 쿼리 최적화

### 4.1 문제 발견 과정

Phase 1에서 인덱스로 개별 쿼리의 효율은 높였지만, **쿼리 자체의 수**는 줄지 않았다. p6spy로 측정한 API별 쿼리 수는 다음과 같았다.

| API | 쿼리 수 | 심각도 |
|-----|---------|--------|
| 피드 ALL (10건) | 16 | 높음 (N+1) |
| 피드 CORE (10건) | 17 | 높음 (N+1) |
| 부모 댓글 (10건) | ~30+ | 심각 (N+1) |

피드 조회의 쿼리 로그를 하나씩 추적한 결과, N+1 패턴을 식별했다.

**피드 TodayQuestion N+1**
```
1. 멤버 조회
2. 게시글 목록 조회 (fetchJoin 적용됨)
3~12. 게시글 10건 각각에 대해 TodayQuestion 개별 조회  <- N+1
13~16. 추가 쿼리
```
게시글을 순회하면서 각각의 `TodayQuestion`을 루프 안에서 개별 조회하고 있었다. 게시글 10건이면 TodayQuestion 쿼리가 10~20회 발생한다.

**댓글 hasChild/replyCount N+1**
```
1. 부모 댓글 목록 조회
2~11. 각 댓글의 replies 컬렉션 lazy loading  <- N+1
12~21. 각 댓글의 member lazy loading  <- N+1
22~31. 각 member의 profileImg lazy loading  <- N+1
```
댓글 10건에 대해 `comment.hasChild()`를 호출하면 `replies` 컬렉션이 lazy loading되고, `comment.getMember().getNickname()`을 호출하면 `member`가, 이어서 `member.getProfileImg()`로 `profileImg`가 각각 lazy loading된다. 총 30회 이상의 추가 쿼리가 발생하는 구조였다.

### 4.2 해결 과정

N+1 문제의 해결 원칙은 "루프 안의 개별 쿼리를 루프 밖의 배치 쿼리로 전환"하는 것이다. 단, 각 상황에 맞는 최적의 방법을 선택해야 한다.

#### 4.2.1 피드 TodayQuestion: 배치 쿼리 + 룩업 맵

**왜 fetchJoin이 아닌 배치 쿼리 + 룩업 맵을 선택했는가?**

`Post`와 `TodayQuestion`은 직접적인 JPA 연관관계가 아니다. 게시글의 `member_id` + `questionOrder` + `selectedDate`를 조합해야 TodayQuestion을 찾을 수 있다. 따라서 fetchJoin을 적용할 수 없고, 별도의 배치 조회 전략이 필요했다.

```java
// Before: 게시글 10건 -> TodayQuestion 10~20회 개별 쿼리
posts.stream().map(postEntity -> {
    todayQuestionRepository.findByMember...(postEntity.getMember(), ...);  // N+1
})

// After: 1회 배치 쿼리 + O(1) 메모리 룩업
List<TodayQuestion> allQuestions = todayQuestionRepository
    .findByMemberIdInAndQuestionOrderInAndSelectedDateBetween(...);
Map<String, TodayQuestion> questionLookup = buildLookupMap(allQuestions);

posts.stream().map(postEntity -> {
    TodayQuestion tq = questionLookup.get(lookupKey);  // O(1)
})
```

핵심은 `(memberId:questionOrder:yearMonth/date)` 형태의 복합 키를 사용한 HashMap 룩업이다. 게시글이 몇 건이든 TodayQuestion 조회는 **정확히 1회**의 SQL로 완료되고, 이후 매핑은 O(1) 메모리 연산이다.

추가로 `@EntityGraph(attributePaths = {"officialQuestion", "personalQuestion"})`를 적용하여, TodayQuestion에서 질문 내용을 가져올 때 발생하는 2차 lazy loading도 차단했다.

#### 4.2.2 댓글 hasChild/replyCount: GROUP BY 배치 쿼리

**왜 replies 컬렉션을 eager loading하지 않았는가?**

대댓글을 전부 로딩하면 불필요한 데이터 전송이 발생한다. 실제로 필요한 것은 "대댓글이 있는가?"와 "대댓글 수"뿐이다. 그래서 컬렉션 자체가 아니라, COUNT 집계만 배치로 가져오는 방식을 선택했다.

```java
// After: 1회 GROUP BY 배치 쿼리
Map<Long, Long> replyCountMap = commentRepository.countRepliesByParentIds(parentIds);
// SQL: SELECT parent_id, COUNT(*) FROM comment WHERE parent_id IN (...) GROUP BY parent_id

long replyCount = replyCountMap.getOrDefault(comment.getId(), 0L);
boolean hasChild = replyCount > 0;
```

`CommentConverter`의 시그니처를 변경하여 `replyCount`를 외부에서 주입받도록 했다. 이렇게 하면 컨버터가 JPA 엔티티의 컬렉션 상태에 의존하지 않게 되어, lazy loading 트리거 위험을 구조적으로 제거한다.

#### 4.2.3 댓글 조회 fetchJoin: member + profileImg

댓글의 `member`와 `member.profileImg`는 항상 함께 사용되므로, QueryDSL fetchJoin으로 한 번에 가져오는 것이 합리적이다.

```java
// After: member + profileImg fetch join 추가
queryFactory.selectFrom(comment)
    .leftJoin(comment.member, member).fetchJoin()
    .leftJoin(member.profileImg, profileImg).fetchJoin()
    .where(...)
    .fetch();
```

### 4.3 트러블슈팅: TodayQuestion NonUniqueResultException

Phase 2 작업 중 예상치 못한 버그를 발견했다. 918MB 시드 데이터로 부하 테스트를 돌리자, CORE 피드 조회에서 **에러율 44%**가 발생한 것이다.

```
"Query did not return a unique result: 29 results were returned"
```

**원인 분석 과정**

에러 스택트레이스를 추적한 결과, `JpaTodayQuestionRepository.findByMemberAndQuestionOrderAndSelectedDateBetween()`가 원인이었다. 이 메서드는 `Optional<TodayQuestion>`을 반환하는데, Spring Data JPA는 `Optional` 반환 시 내부적으로 `getSingleResult()`를 호출한다. 결과가 2건 이상이면 `NonUniqueResultException`이 발생한다.

문제의 근본 원인은 두 가지였다.
1. `(member, questionOrder, selectedDate)` 조합에 **UNIQUE 제약조건이 없었다**
2. `Between` 쿼리로 월 단위 범위 검색 시, 동일 조합으로 여러 레코드가 존재할 수 있었다

**왜 개발 중에 발견되지 않았는가?**

빈 DB나 소량 데이터에서는 중복 데이터가 없어 항상 0~1건만 반환되었다. 918MB 규모의 시드 데이터를 넣고 나서야 중복 조합이 존재하게 되었고, 그때 비로소 버그가 드러났다. **대량 데이터 테스트의 중요성**을 실감한 순간이었다.

**해결**

```java
// Before: 결과가 여러 건이면 NonUniqueResultException
Optional<TodayQuestion> findByMemberAndQuestionOrderAndSelectedDateBetween(...);

// After: findFirst 접두사 -> Spring Data가 LIMIT 1 적용
Optional<TodayQuestion> findFirstByMemberAndQuestionOrderAndSelectedDateBetween(...);
```

동시에, 호출부의 안전하지 않은 `.get()` 호출도 수정했다.

```java
// Before: 데이터 없으면 NoSuchElementException
.get();

// After: 값이 있을 때만 처리
.ifPresent(questions::add);
```

추가로, `NotificationReminderScheduler`가 도메인 인터페이스(`TodayQuestionRepository`)가 아닌 인프라 구현체(`JpaTodayQuestionRepository`)를 직접 참조하는 아키텍처 위반도 발견하여 함께 수정했다.

이 수정으로 에러율이 **7.75% -> 0.01%**로 정상화되었다.

### 4.4 검증 결과

**쿼리 수 개선 (p6spy 측정)**

| API | Before | After | 감소율 |
|-----|--------|-------|--------|
| Feed ALL (10건) | 16 | **7** | **-56%** |
| Feed CORE (10건) | 17 | **7** | **-59%** |
| 부모 댓글 (10건) | ~30+ | **4** | **-87%** |

**부하 테스트 결과**

| 지표 | Baseline | Phase 1 | Phase 2 | 총 개선율 |
|------|----------|---------|---------|-----------|
| Feed p95 | 489ms | 245ms | **290ms** | -40.7% |
| http_req_duration p95 | 518ms | 266ms | **317ms** | -38.8% |
| 처리량 | 86 req/s | 93 req/s | **93 req/s** | +8.1% |

**Phase 2 단독으로 보면 p95가 Phase 1보다 약간 높아진 이유**

쿼리 수는 56~87% 감소했는데 p95는 소폭 상승한 것은 의아할 수 있다. 이는 **배치 쿼리의 IN 절이 개별 쿼리보다 단건 실행 시간이 길기 때문**이다. 하지만 이것이 의미 없는 것은 아니다.

- 쿼리 수 감소로 **DB 커넥션 점유 시간이 줄어**, 동시 접속이 더 많은 환경에서 효과가 극대화된다
- 네트워크 라운드트립 횟수가 줄어, DB가 원격 서버에 있는 실 배포 환경에서 더 큰 차이를 만든다
- 개별 쿼리 N회 vs 배치 쿼리 1회의 트레이드오프에서, **확장성(scalability) 측면에서 배치 쿼리가 압도적으로 유리하다**

---

## 5. Phase 3: 비동기 이벤트 전환

### 5.1 문제 발견 과정

Phase 2까지 쿼리 계층의 최적화를 완료한 뒤, 다음 병목 지점을 분석했다. 좋아요와 댓글 작성 API의 흐름은 다음과 같았다.

```
[API 요청] -> [비즈니스 로직] -> [알림 생성 (DB)] -> [디바이스 조회 (DB)] -> [FCM 전송 (외부 I/O)] -> [응답]
```

여기서 문제는 **FCM 전송이 외부 네트워크 I/O**라는 점이다. FCM 서버 응답이 느리거나 타임아웃이 발생하면, 사용자의 좋아요/댓글 API 응답이 함께 지연된다. 알림은 비즈니스의 핵심 흐름이 아님에도 불구하고, 동기 실행으로 인해 API 응답 경로를 블로킹하고 있었다.

또한 아키텍처 관점에서도 문제가 있었다.

```java
// PostLikeCommandServiceImpl의 의존성: 5개
PostRepository, PostLikeRepository, DeviceRepository, NotificationCommandService, FcmService
```

좋아요 서비스가 알림 생성, 디바이스 조회, FCM 전송까지 직접 알고 있는 것은 **단일 책임 원칙(SRP) 위반**이다. 알림 로직이 변경될 때마다 좋아요 서비스를 수정해야 하고, 테스트 시에도 5개의 의존성을 모두 모킹해야 한다.

### 5.2 해결 과정: Spring Event + @Async

**왜 메시지 큐(Kafka, RabbitMQ)가 아닌 Spring Event를 선택했는가?**

현재 서비스 규모에서 외부 메시지 브로커는 운영 복잡도 대비 이점이 크지 않다. Spring Event는 추가 인프라 없이 프로세스 내에서 이벤트 기반 아키텍처를 구현할 수 있고, 향후 트래픽이 증가하면 Kafka로 전환하기도 용이하다. **현재 규모에 맞는 적정 기술**을 선택한 것이다.

#### 5.2.1 이벤트 클래스 설계

```java
// NotificationEvent — 정적 팩토리 메서드로 이벤트 타입별 생성
NotificationEvent.like(postId, senderId, receiverId)
NotificationEvent.comment(postId, commentId, senderId, receiverId)
NotificationEvent.reply(postId, commentId, senderId, receiverId)
```

엔티티가 아닌 **ID만 전달**하도록 설계했다. 이벤트 리스너가 별도 스레드에서 실행되므로, 원래 트랜잭션의 영속성 컨텍스트가 공유되지 않는다. 엔티티를 전달하면 `LazyInitializationException`이 발생할 수 있기 때문에, 최소한의 식별자만 전달하고 리스너에서 필요한 엔티티를 직접 조회하는 방식이 안전하다.

#### 5.2.2 이벤트 리스너

```java
@Async("notificationExecutor")
@TransactionalEventListener(phase = AFTER_COMMIT)
public void handleNotification(NotificationEvent event) {
    try {
        // 알림 생성 + FCM 전송
    } catch (Exception e) {
        log.error("알림 처리 실패", e);
        // 알림 실패가 비즈니스 로직에 영향을 주지 않도록 격리
    }
}
```

**왜 `@EventListener`가 아닌 `@TransactionalEventListener(AFTER_COMMIT)`인가?**

`@EventListener`는 이벤트 발행 시점에 즉시 실행되므로, 트랜잭션이 아직 커밋되지 않은 상태에서 리스너가 동작한다. 만약 리스너에서 좋아요 데이터를 조회하면, 아직 커밋되지 않은 데이터를 읽지 못해 오류가 발생할 수 있다. `AFTER_COMMIT`으로 설정하면 **트랜잭션이 성공적으로 커밋된 후에만** 리스너가 실행되어 데이터 정합성이 보장된다.

**왜 별도의 notificationExecutor를 만들었는가?**

기존 `mailExecutor`와 알림 처리의 스레드 풀을 분리하여, 한쪽의 부하가 다른 쪽에 영향을 주지 않도록 격리했다. `core:4, max:10, queue:100`으로 설정하여 알림 폭주 시에도 최대 10개 스레드 + 100개 큐로 제어된다.

```java
@Bean(name = "notificationExecutor")
public ThreadPoolTaskExecutor notificationExecutor() {
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(10);
    executor.setQueueCapacity(100);
    executor.setThreadNamePrefix("Notification-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(30);
}
```

#### 5.2.3 서비스 리팩토링

```
// Before
[API 요청] -> [비즈니스 로직] -> [알림 생성 (DB)] -> [디바이스 조회 (DB)] -> [FCM 전송] -> [응답]

// After
[API 요청] -> [비즈니스 로직] -> [이벤트 발행] -> [응답]
                                     |
                                     v (AFTER_COMMIT, 별도 스레드)
                              [알림 생성] -> [FCM 전송]
```

### 5.3 아키텍처 개선 효과

**의존성 변화**

| 서비스 | Before | After |
|--------|--------|-------|
| PostLikeCommandServiceImpl | 5개 (PostRepo, PostLikeRepo, **DeviceRepo**, **NotificationService**, **FcmService**) | 3개 (PostRepo, PostLikeRepo, **EventPublisher**) |
| CommentCommandServiceImpl | 6개 (CommentRepo, PostRepo, MemberRepo, **DeviceRepo**, **NotificationService**, **FcmService**) | 4개 (CommentRepo, PostRepo, MemberRepo, **EventPublisher**) |

비즈니스 서비스가 알림 인프라(FCM, 디바이스)에 대한 의존을 완전히 제거했다. 이로 인해 다음이 가능해졌다.

1. **장애 격리**: FCM 서버 장애가 좋아요/댓글 기능에 영향을 주지 않음
2. **테스트 용이성**: 모킹해야 할 의존성이 줄어 단위 테스트가 간결해짐
3. **확장성**: 알림 채널 추가(이메일, SMS 등) 시 리스너만 추가하면 되고, 비즈니스 서비스는 수정 불필요

### 5.4 검증 결과

| 시나리오 | Baseline p95 | Phase 2 p95 | Phase 3 p95 | 총 개선율 |
|----------|-------------|-------------|-------------|-----------|
| Feed | 489ms | 290ms | **262ms** | **-46.4%** |
| Profile | 438ms | 185ms | **141ms** | **-67.8%** |
| Notifications | 439ms | 187ms | **154ms** | **-64.9%** |
| Questions | 419ms | 262ms | **190ms** | **-54.7%** |
| Search | 464ms | 219ms | **159ms** | **-65.7%** |

| 지표 | Baseline | Phase 3 | 총 변화 |
|------|----------|---------|---------|
| http_req_duration p95 | 518ms | **270ms** | **-47.9%** |
| 처리량 | 86 req/s | **94 req/s** | **+9.3%** |
| 총 요청 | 20,269 | **21,912** | **+8.1%** |

비동기 전환으로 Phase 2 대비 p95가 317ms에서 270ms로 추가 개선되었다. 특히 Phase 2에서 약간 상승했던 수치가 Phase 3에서 다시 낮아져, 전체적으로 Baseline 대비 **47.9% 개선**이라는 결과를 얻었다.

---

## 6. Phase 4: 캐싱 레이어 (Caffeine Local Cache)

### 6.1 문제 발견 과정

Phase 3까지 쿼리 수 최적화와 비동기 전환을 완료했지만, 피드 조회 시 **매 요청마다 팔로잉/서클 ID를 DB에서 조회**하는 패턴이 남아 있었다. 피드 쿼리의 WHERE 절에는 follow 테이블을 대상으로 한 서브쿼리가 3개 인라인되어 있었다.

```sql
-- ALL 피드: 내가 팔로우하는 사용자 ID 조회 (서브쿼리 1)
SELECT following_id FROM follow WHERE follower_id = ?

-- CORE 피드: 서클 팔로잉 ID 조회 (서브쿼리 2)
SELECT following_id FROM follow WHERE follower_id = ? AND is_circle = true

-- CIRCLE 게시글 공개범위 필터링 (서브쿼리 3)
SELECT following_id FROM follow WHERE follower_id = ? AND is_circle = true
```

**팔로잉 목록은 자주 변경되지 않는 데이터**다. 사용자가 팔로우/언팔로우하는 빈도는 피드 조회 빈도에 비해 매우 낮다. 그럼에도 매 피드 요청마다 동일한 follow 테이블을 반복 조회하고 있었다.

### 6.2 해결 과정: 캐시 친화적 쿼리 구조로 전환

단순히 "쿼리 결과를 캐시한다"가 아니라, **쿼리 구조 자체를 캐시 친화적으로 재설계**하는 것이 핵심이었다.

**왜 서브쿼리를 그대로 캐싱할 수 없는가?**

기존 서브쿼리는 메인 쿼리(피드 게시글 조회) 내부에 인라인되어 있었다. 서브쿼리만 분리하여 캐싱하려면, 쿼리 구조 자체를 변경해야 한다. 즉:

1. 서브쿼리를 별도의 독립 쿼리로 분리
2. 분리된 쿼리의 결과(ID 리스트)를 캐싱
3. 메인 쿼리는 캐시된 ID 리스트를 `IN` 절로 받아서 실행

```
// Before: 서브쿼리 인라인 (매번 follow 테이블 접근)
SELECT * FROM post WHERE member_id IN (SELECT following_id FROM follow WHERE ...)

// After: 캐시된 ID 리스트 사용 (캐시 히트 시 follow 테이블 접근 0회)
List<Long> followingIds = cache.get("followingIds:" + memberId);  // 캐시 히트
SELECT * FROM post WHERE member_id IN (:followingIds)
```

**왜 Caffeine(로컬 캐시)인가?**

Redis 같은 분산 캐시를 선택하지 않은 이유는:
- **단일 서버 구성**: 현재 아키텍처에서 캐시 일관성 문제가 없음
- **초저지연**: 네트워크 없이 힙 메모리 접근 (나노초 단위)
- **운영 부담 없음**: 추가 인프라 불필요
- **Spring Cache 통합**: `@Cacheable`/`@CacheEvict`로 선언적 캐시 관리

```java
CaffeineCacheManager manager = new CaffeineCacheManager("followingIds", "circleIds");
manager.setCaffeine(Caffeine.newBuilder()
        .maximumSize(5000)        // 동시 사용자 수 기준 충분한 크기
        .expireAfterWrite(5, TimeUnit.MINUTES)  // 팔로우 변경 빈도 대비 적절한 TTL
        .recordStats());          // 캐시 히트율 모니터링
```

### 6.3 캐시 무효화 전략

캐시의 가장 어려운 문제는 **"언제 캐시를 비울 것인가"**다. 잘못된 캐시 무효화는 오래된 팔로잉 목록으로 피드를 보여주는 문제를 일으킨다.

팔로잉/서클 ID 캐시에 영향을 주는 이벤트를 분석했다.

| 이벤트 | 영향 받는 캐시 | 대상 사용자 |
|--------|--------------|------------|
| `follow(A, B)` | followingIds | A (팔로워) |
| `unfollow(A, B)` | followingIds + circleIds | A (팔로워) + B (서클 해제 가능) |
| `updateCircleStatus(A, B)` | circleIds | A (본인) + B (서클 상태 변경 대상) |
| `block(A, B)` | followingIds + circleIds | A (차단자) + B (피차단자) |

TTL 5분은 자연 만료 시간이며, 팔로우/언팔로우/서클 변경/차단 시에는 `@CacheEvict`가 즉시 트리거되어 다음 요청에서 최신 데이터가 반영된다. TTL은 명시적 이벤트 없이 캐시가 무한히 남는 것을 방지하는 안전장치 역할이다.

Spring의 `@CacheEvict`와 `@Caching`으로 선언적으로 구현했다.

```java
@CacheEvict(value = "followingIds", key = "#member.id")
public Follow follow(Member member, Long targetId) { ... }

@Caching(evict = {
    @CacheEvict(value = "followingIds", key = "#member.id"),
    @CacheEvict(value = "circleIds", key = "#member.id"),
    @CacheEvict(value = "circleIds", key = "#targetId")
})
public void unfollow(Member member, Long targetId) { ... }

@Caching(evict = {
    @CacheEvict(value = "circleIds", key = "#member.id"),
    @CacheEvict(value = "circleIds", key = "#targetId")
})
public void updateCircleStatus(Member member, Long targetId, boolean isCircle) { ... }

@Caching(evict = {
    @CacheEvict(value = "followingIds", key = "#member.id"),
    @CacheEvict(value = "circleIds", key = "#member.id"),
    @CacheEvict(value = "followingIds", key = "#targetId"),
    @CacheEvict(value = "circleIds", key = "#targetId")
})
public Block block(Member member, Long targetId) { ... }
```

### 6.4 검증 결과

**쿼리 수 비교**

| 상태 | 피드 쿼리 수 | follow 테이블 접근 |
|------|------------|------------------|
| Cache MISS (첫 요청) | 9 | 2회 (별도 쿼리) |
| Cache HIT (반복 요청) | **7** | **0회** |

**부하 테스트 결과**

| 시나리오 | Baseline p95 | Phase 3 p95 | Phase 4 p95 | 총 개선율 |
|----------|-------------|-------------|-------------|-----------|
| Feed | 489ms | 262ms | **246ms** | **-49.7%** |
| Profile | 438ms | 141ms | **127ms** | **-71.0%** |

**캐시 효과가 제한적인 이유와 실제 가치**

부하 테스트에서 p95 개선이 6% 수준인 이유는, Phase 1에서 추가한 복합 인덱스 덕분에 follow 서브쿼리 자체가 이미 빨랐기 때문이다(rows:2, filtered:100%). 인덱스가 잘 잡힌 상태에서의 캐시 효과는 극적이지 않다.

하지만 **캐시의 진짜 가치**는 단순한 지연 시간 개선이 아니라:
1. **DB 커넥션 점유 시간 감소**: 캐시 히트 시 follow 쿼리를 아예 실행하지 않으므로, DB 커넥션 풀의 여유가 증가
2. **높은 동시 접속 시 안정성**: 200~500 VUs 이상에서 DB 커넥션 경합이 심해질 때, 캐시가 DB 부하를 흡수
3. **쿼리 구조 분리**: follow 조회와 post 조회가 독립적으로 최적화 가능

이는 **"현재 환경에서 극적 차이가 없더라도, 서비스 규모가 커질 때 효과가 증폭되는 투자"**라는 판단이다.

### 6.5 게시글 상세 조회 쿼리 최적화

피드 캐싱 이후, 게시글 **상세 조회(Post Detail)** API의 쿼리 패턴을 분석했다. 피드에서 게시글을 클릭해 상세 화면으로 이동하는 흐름은 사용자 경험의 핵심 동선이다.

**문제 1: 질문 컨텐츠 조회 — 4개 개별 쿼리**

게시글에 연결된 4개의 질문(월간 고정 3개 + 일간 1개)을 for 루프로 하나씩 조회하고 있었다.

```java
// Before: 4개 개별 쿼리
for(int i = 1; i < 4; i++) {
    todayQuestionRepository.findByMemberAndQuestionOrderAndSelectedDateBetween(member, i, ...);
}
todayQuestionRepository.findByMemberAndQuestionOrderAndSelectedDate(member, 4, date);
```

Phase 2에서 피드용으로 만든 `@EntityGraph` 배치 메서드가 이미 존재했다. 이를 재활용하여 1개 쿼리 + 메모리 필터링으로 교체했다.

```java
// After: 1개 배치 쿼리 + @EntityGraph
List<TodayQuestion> allQuestions = todayQuestionRepository
    .findByMemberIdInAndQuestionOrderInAndSelectedDateBetween(
        List.of(member.getId()), List.of(1, 2, 3, 4), startOfMonth, endOfMonth);
// questionOrder 4는 해당 날짜만 필터
return allQuestions.stream()
    .filter(q -> q.getQuestionOrder() != 4 || q.getSelectedDate().equals(date))
    .toList();
```

**문제 2: 좋아요 체크 — 불필요한 엔티티 로딩 3 쿼리**

`checkIsLiked(memberId, postId)` 메서드가 ID를 이미 알고 있음에도 Member와 Post 엔티티를 각각 DB에서 로딩한 뒤 `existsByMemberAndPost(Member, Post)`를 호출하고 있었다.

```java
// Before: 3 쿼리 (Member 로딩 + Post 로딩 + EXISTS)
Member member = memberRepository.findById(memberId).orElseThrow(...);  // 쿼리 1
Post post = postRepository.findById(postId).orElseThrow(...);          // 쿼리 2
return postLikeRepository.existsByMemberAndPost(member, post);         // 쿼리 3

// After: 1 쿼리 (ID 기반 EXISTS)
return postLikeRepository.existsByMemberIdAndPostId(memberId, postId); // 쿼리 1
```

JPA 네이밍 컨벤션(`existsByMemberIdAndPostId`)으로 Spring Data가 자동으로 FK 컬럼 기반 EXISTS 쿼리를 생성한다. 이 변경으로 `PostQueryServiceImpl`에서 `MemberRepository` 의존성 자체를 제거할 수 있었다.

**검증 결과**

| 항목 | Before | After | 감소율 |
|------|--------|-------|--------|
| 질문 컨텐츠 조회 | 4 쿼리 | 1 쿼리 | **-75%** |
| 좋아요 체크 | 3 쿼리 | 1 쿼리 | **-67%** |
| **상세 조회 전체** | **~10+ 쿼리** | **~6 쿼리** | **-40%** |

---

## 6.6 Phase 5: 배치 최적화

배치/스케줄러 작업의 메모리 효율성, 실행 속도, 쿼리 효율성을 개선했다.

### 임시 게시글 정리 — 청크 기반 처리

`cleanupOldTempPosts()`가 전체 TEMP 게시글을 한번에 메모리에 로딩하고 있어 대량 데이터 시 OOM 위험이 있었다. 100건 단위 Page 기반 청크 처리로 전환하고, 실패 건은 스킵하여 나머지가 계속 처리되도록 했다.

```java
final int CHUNK_SIZE = 100;
Page<Post> postPage;
do {
    postPage = postRepository.findTempPostsPageable(
            PostStatus.TEMP, cutoffDate.plusDays(1).atStartOfDay(),
            PageRequest.of(0, CHUNK_SIZE));  // 삭제 작업이므로 항상 page=0
    for (Post post : postPage.getContent()) {
        try {
            postRepository.delete(post);
            deletedCount++;
        } catch (Exception e) {
            failedCount++;  // 스킵 후 계속 진행
        }
    }
} while (postPage.hasNext());
```

### 일일 배치 병렬 실행

4개 통계 작업(일일 통계, 고정질문 통계, 랜덤질문 통계, 선택일기 통계)이 순차 실행되고 있었다. `CompletableFuture.allOf()`와 전용 `batchExecutor` 스레드풀(core:4, max:8)로 병렬 실행하여 총 소요시간을 `T1+T2+T3+T4` → `max(T1,T2,T3,T4)`로 단축했다.

### 리마인더 스케줄러 N+1 쿼리 제거

5분마다 실행되는 `NotificationReminderScheduler`가 매칭된 멤버 N명에 대해 각각 12개 쿼리를 수행하고 있었다 (질문 4개 + 답변 8개). 배치 쿼리 2회 + Map 기반 O(1) 룩업으로 전환했다.

```java
// Before: N × 12 쿼리 (50명 매칭 시 ~600 쿼리)
for (Member member : targets) {
    hasTodayQuestions(member);      // 4 쿼리
    hasUnansweredQuestions(member);  // 8 쿼리
}

// After: 2 쿼리 (멤버 수와 무관)
Map<Long, Set<Integer>> questionMap = batchFetchQuestions(memberIds);
Map<Long, Set<Integer>> answerMap = batchFetchAnswers(memberIds);
// O(1) Map 룩업으로 질문/답변 완성 여부 확인
```

**검증 결과**

| 대상 | Before | After | 감소율 |
|------|--------|-------|--------|
| 리마인더 쿼리 (10,000명) | ~120,000 쿼리 | **2 쿼리** | **-99.99%** |
| 리마인더 실행 시간 (10,000명) | — | **27초** (2 쿼리 + FCM 전송 포함) | 쿼리 병목 제거 |
| 배치 실행 시간 (순차→병렬) | 2,677ms | **1,157ms** | **-56.8%** |
| TEMP 게시글 메모리 | 전체 로딩 | 100건 청크 | **OOM 방지** |

배치 병렬 실행 측정은 918MB 시드 데이터(10,000명) 환경에서 4개 통계 작업을 순차 실행(2,677ms)한 뒤 동일 환경에서 `CompletableFuture.allOf()` 병렬 실행(1,157ms)을 비교한 결과다. 리마인더 스케줄러는 10,000명 전원 매칭 시나리오에서 배치 쿼리 2회 + FCM 전송까지 27초에 완료되었다.

---

## 7. 전체 성과 요약

### 7.1 Phase별 p95 응답시간 변화

| Phase | http_req p95 | Feed p95 | 처리량 |
|-------|-------------|----------|--------|
| Baseline | 518ms | 489ms | 86 req/s |
| Phase 1 (인덱스+트랜잭션) | 266ms (-48.6%) | 245ms (-49.9%) | 93 req/s (+8.1%) |
| Phase 2 (N+1 해결) | 317ms (-38.8%) | 290ms (-40.7%) | 93 req/s (+8.1%) |
| Phase 3 (비동기 전환) | 270ms (-47.9%) | 262ms (-46.4%) | 94 req/s (+9.3%) |
| Phase 4 (캐싱) | 296ms (-42.9%) | 246ms (-49.7%) | 94 req/s (+9.3%) |
| Phase 5 (배치 최적화) | — | — | **배치 56.8% 단축, 쿼리 99.99% 감소** |

### 7.2 쿼리 수 변화

| API | Before | After | 감소율 |
|-----|--------|-------|--------|
| 피드 ALL (10건) | 16 | **7** (캐시 히트 시) | **-56%** |
| 피드 CORE (10건) | 17 | **7** (캐시 히트 시) | **-59%** |
| 부모 댓글 (10건) | ~30+ | **4** | **-87%** |
| 게시글 상세 조회 | ~10+ | **~6** | **-40%** |
| 리마인더 스케줄러 (10,000명) | ~120,000 | **2** | **-99.99%** |

### 7.3 핵심 수치

| 지표 | Before | After | 변화 |
|------|--------|-------|------|
| Feed p95 응답시간 | 489ms | **246ms** | **-49.7%** |
| 처리량 | 86 req/s | **94 req/s** | **+9.3%** |
| 피드 쿼리 수 | 16~17 | **7** | **-56~59%** |
| 댓글 쿼리 수 | ~30+ | **4** | **-87%** |
| 상세 조회 쿼리 수 | ~10+ | **~6** | **-40%** |
| TodayQuestion EXPLAIN rows | 212 | **1** | **-99.5%** |
| 에러율 | 7.75% (버그 포함) | **0.01%** | **정상화** |
| Follow DB 접근 (캐시 히트) | 매 요청 | **0회** | **완전 제거** |
| 리마인더 쿼리 (10,000명) | ~120,000 | **2** | **-99.99%** |
| 배치 실행 시간 | 2,677ms (순차) | **1,157ms (병렬)** | **-56.8%** |

---

## 8. 배운 점과 기술적 성장

### 8.1 "측정 없는 최적화는 추측에 불과하다"

최적화의 가장 중요한 첫 단계는 **현재 상태를 정확히 측정하는 것**이다. p6spy로 쿼리 수를 세고, EXPLAIN으로 실행 계획을 분석하고, k6로 부하 테스트를 돌려 p95/처리량을 측정한 뒤에야 "어디를 고쳐야 하는지"가 명확해졌다. 느낌이 아니라 숫자로 의사결정하는 습관이 가장 큰 수확이었다.

### 8.2 "대량 데이터에서만 드러나는 문제가 있다"

TodayQuestion `NonUniqueResultException`은 빈 DB에서는 절대 발견되지 않는 버그였다. 918MB 시드 데이터를 넣고 나서야 중복 데이터로 인한 에러가 드러났다. 이 경험은 **실 서비스 규모의 테스트 환경이 왜 필요한지**를 체감하게 해주었다. Spring Data JPA의 `Optional` 반환이 단일 결과를 보장하지 않는다는 사실도 이 과정에서 학습했다.

### 8.3 "최적화는 트레이드오프의 연속이다"

Phase 2에서 쿼리 수를 56~87% 줄였음에도 p95가 소폭 상승한 사례는 흥미로웠다. 배치 쿼리의 IN 절이 개별 쿼리보다 단건 실행 시간이 길지만, DB 커넥션 점유 시간과 네트워크 라운드트립 감소로 고부하 환경에서 더 큰 이점을 가진다. **단일 지표가 아니라 전체적인 시스템 특성을 고려한 판단**이 중요하다는 것을 배웠다.

### 8.4 "비동기 전환은 성능과 아키텍처를 동시에 개선한다"

Spring Event + @Async 전환은 응답 시간 개선뿐 아니라, 서비스 간 결합도를 크게 낮추는 효과가 있었다. `@TransactionalEventListener(AFTER_COMMIT)`과 ID 기반 이벤트 전달이라는 설계 선택은, 데이터 정합성과 `LazyInitializationException` 방지라는 기술적 근거에서 나온 것이다. 단순히 "비동기로 바꿨다"가 아니라, **왜 이 방식이어야 하는지**를 근거로 설명할 수 있게 되었다.

### 8.5 "인덱스는 가장 비용 대비 효과가 큰 최적화다"

코드 한 줄 수정 없이 인덱스 추가만으로 p95가 48.6% 감소한 것은 인상적이었다. 하지만 이는 **적절한 복합 인덱스 설계**가 전제되어야 한다. WHERE 절의 등치/범위 조건 분석, 컬럼 순서 배치, 커버링 인덱스 고려 등 인덱스 설계에는 쿼리 패턴에 대한 깊은 이해가 필요하다. EXPLAIN 분석 능력이 이 모든 것의 기반이 된다.

### 8.6 "캐시는 마법이 아니라 전략이다"

Phase 4에서 캐시를 도입했을 때, 부하 테스트 결과의 개선폭은 기대보다 작았다(Feed p95 6% 개선). 이미 인덱스로 최적화된 쿼리에 캐시를 올려도 극적 효과를 얻기 어렵다는 것을 배웠다. 하지만 이를 "실패"로 보지 않는 이유는, **캐시의 가치는 평균적 상황이 아니라 극한 상황에서 드러나기 때문**이다. DB 커넥션 풀이 고갈되는 고부하 시나리오에서, 캐시는 DB로 가는 요청 자체를 차단하여 시스템 전체의 안정성을 확보한다. 또한 쿼리 구조를 캐시 친화적으로 분리하는 과정 자체가, 서브쿼리의 의존성을 명시적으로 드러내고 각각 독립적으로 최적화할 수 있는 구조를 만들어낸다.
