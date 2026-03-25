# ADR: 배치 프레임워크 기술 선택 — @Scheduled vs Quartz vs Spring Batch

> 작성일: 2026-02-23
> 상태: 채택 (@Scheduled + CompletableFuture)

## 1. 배경

CoreDisc의 배치 작업은 3가지이다:

| 작업 | 주기 | 처리 내용 |
|------|------|----------|
| 일간 통계 생성 (4종) | 매일 00:00 | 전날 답변 기반 통계 집계 → 저장 |
| 월간 Disc 생성 | 매월 1일 00:00 | 전월 답변 기반 디스크 데이터 생성 |
| 임시글 정리 | 매일 00:00 | 7일 이상 된 TEMP 게시글 삭제 |
| 리마인더 알림 | 5분마다 | 미응답 유저에게 FCM 푸시 |

Phase 5에서 이 배치들을 최적화하면서 **Spring Batch 도입 여부**를 검토했다.

---

## 2. 셋은 해결하는 문제 자체가 다르다

@Scheduled, Quartz, Spring Batch는 경쟁 관계가 아니라 **레이어가 다르다**.

```
┌────────────────────────────────────────────────┐
│       "언제 실행할 것인가" — Trigger 레이어       │
│                                                  │
│   @Scheduled              Quartz                 │
│   (Spring 내장)           (별도 라이브러리)         │
│                                                  │
├────────────────────────────────────────────────┤
│    "데이터를 어떻게 처리할 것인가" — Processing     │
│                                                  │
│   Spring Batch                                   │
│   (데이터 처리 프레임워크)                          │
│                                                  │
└────────────────────────────────────────────────┘
```

Spring Batch는 **스케줄러가 아니다**. 데이터 처리 프레임워크다.
Spring Batch Job을 실행하려면 결국 @Scheduled든 Quartz든 별도 트리거가 필요하다.

---

## 3. @Scheduled vs Quartz — 트리거 레이어 비교

| 비교 항목 | @Scheduled | Quartz |
|----------|-----------|--------|
| **저장소** | 없음 (메모리) | DB 테이블 11개 (QRTZ_*) |
| **서버 2대 배포** | 양쪽 모두 실행 (중복) | 클러스터 락으로 1대만 실행 |
| **서버 다운 후 복구** | 놓친 실행은 스킵 | misfire policy로 보상 실행 가능 |
| **동적 스케줄 변경** | 불가 (코드 재배포 필요) | 런타임 cron 변경 가능 |
| **의존성** | Spring 기본 내장 | spring-boot-starter-quartz 추가 |
| **DB 쿼리 비용** | 0 | 매 실행마다 락 획득/해제 쿼리 |

### CoreDisc에서의 판단

CoreDisc는 현재 **단일 서버** 배포다. 서버가 1대인 환경에서 Quartz의 핵심 장점(클러스터 락, misfire 보상)은 사용할 일이 없다. DB 테이블 11개와 락 쿼리 오버헤드만 추가된다.

**결론: @Scheduled 유지.** 서버가 2대 이상으로 스케일 아웃하는 시점에 Quartz 또는 ShedLock 검토.

---

## 4. @Scheduled 직접 구현 vs Spring Batch — 처리 레이어 비교

여기가 핵심이다. "배치 처리 로직을 직접 짜는 것"과 "Spring Batch 프레임워크를 사용하는 것"의 차이.

### 4-1. Spring Batch가 요구하는 인프라 비용

**메타데이터 테이블 6개 + 시퀀스 3개:**

```sql
-- Spring Batch 필수 스키마 (spring-batch-core 내장 DDL)
CREATE TABLE BATCH_JOB_INSTANCE (...);        -- Job 정의
CREATE TABLE BATCH_JOB_EXECUTION (...);       -- Job 실행 이력
CREATE TABLE BATCH_JOB_EXECUTION_PARAMS (...);-- 실행 파라미터
CREATE TABLE BATCH_JOB_EXECUTION_CONTEXT (...);-- Job 상태 직렬화
CREATE TABLE BATCH_STEP_EXECUTION (...);      -- Step 실행 이력
CREATE TABLE BATCH_STEP_EXECUTION_CONTEXT (...);-- Step 상태 (체크포인트)
CREATE TABLE BATCH_JOB_EXECUTION_SEQ (...);   -- 시퀀스
CREATE TABLE BATCH_JOB_SEQ (...);
CREATE TABLE BATCH_STEP_EXECUTION_SEQ (...);
```

이 테이블들은 선택이 아니라 **필수**다. Spring Batch는 `JobRepository`를 통해 모든 실행 상태를 DB에 기록하고, 이것이 재시작/체크포인트의 기반이 된다.

**Chunk 커밋마다 발생하는 추가 쿼리:**

```
매 chunk 커밋 시:
  1. UPDATE BATCH_STEP_EXECUTION SET ... (read_count, write_count 갱신)
  2. UPDATE BATCH_STEP_EXECUTION_CONTEXT SET ... (ExecutionContext 직렬화 저장)

10,000건을 chunk=100으로 처리하면:
  → 비즈니스 트랜잭션 100회 + 메타데이터 UPDATE 200회 = 총 300회 트랜잭션
```

이건 "느려진다"의 문제가 아니라, **비즈니스 로직과 무관한 I/O가 강제된다**는 뜻이다.

### 4-2. 코드 복잡도 비교 — 동일한 작업의 구현량 차이

**임시글 청크 삭제 — 현재 구현 (직접):**

```java
// PostCommandServiceImpl.java — 핵심 로직 10줄
final int CHUNK_SIZE = 100;
Page<Post> postPage;
do {
    postPage = postRepository.findTempPostsPageable(
            PostStatus.TEMP, cutoffDate.plusDays(1).atStartOfDay(),
            PageRequest.of(0, CHUNK_SIZE));
    for (Post post : postPage.getContent()) {
        try {
            postRepository.delete(post);
            deletedCount++;
        } catch (Exception e) {
            failedCount++;
            log.error("TEMP 게시글 삭제 실패 - ID: {}", post.getId());
        }
    }
} while (postPage.hasNext());
```

**같은 작업을 Spring Batch로 구현하면:**

```java
// --- 1. Job 설정 ---
@Bean
public Job tempPostCleanupJob(JobRepository jobRepository, Step cleanupStep) {
    return new JobBuilder("tempPostCleanupJob", jobRepository)
            .start(cleanupStep)
            .build();
}

// --- 2. Step 설정 ---
@Bean
public Step cleanupStep(JobRepository jobRepository,
                        PlatformTransactionManager tm) {
    return new StepBuilder("cleanupStep", jobRepository)
            .<Post, Post>chunk(100, tm)
            .reader(tempPostReader(null))
            .writer(tempPostWriter())
            .faultTolerant()
            .skip(Exception.class)
            .skipLimit(100)
            .listener(cleanupStepListener())
            .build();
}

// --- 3. ItemReader ---
@Bean
@StepScope
public RepositoryItemReader<Post> tempPostReader(
        @Value("#{jobParameters['cutoffDate']}") String cutoffDate) {
    RepositoryItemReader<Post> reader = new RepositoryItemReader<>();
    reader.setRepository(jpaPostRepository);
    reader.setMethodName("findByStatusAndCreatedAtBefore");
    reader.setArguments(List.of(PostStatus.TEMP, LocalDate.parse(cutoffDate)...));
    reader.setPageSize(100);
    reader.setSort(Map.of("id", Sort.Direction.ASC));
    return reader;
}

// --- 4. ItemWriter ---
@Bean
public ItemWriter<Post> tempPostWriter() {
    return posts -> {
        for (Post post : posts) {
            postRepository.delete(post);
        }
    };
}

// --- 5. Listener (선택) ---
@Bean
public StepExecutionListener cleanupStepListener() { ... }

// --- 6. Job 실행 (스케줄러에서) ---
@Scheduled(cron = "0 0 0 * * *")
public void runCleanup() {
    JobParameters params = new JobParametersBuilder()
            .addString("cutoffDate", LocalDate.now().minusDays(7).toString())
            .addLong("timestamp", System.currentTimeMillis())  // 재실행을 위한 유니크 키
            .toJobParameters();
    jobLauncher.run(tempPostCleanupJob, params);
}
```

**10줄 → 약 50줄.** 코드량만의 문제가 아니라, Spring Batch의 설정 컨벤션(Bean 등록, @StepScope, JobParameters 바인딩)에 대한 이해가 필요하다.

### 4-3. 병렬 처리 비교

**현재 구현 — CompletableFuture:**

```java
CompletableFuture.allOf(
    CompletableFuture.runAsync(() -> service.taskA(), executor),
    CompletableFuture.runAsync(() -> service.taskB(), executor),
    CompletableFuture.runAsync(() -> service.taskC(), executor),
    CompletableFuture.runAsync(() -> service.taskD(), executor)
).join();
```

**Spring Batch — Split Flow:**

```java
Flow flowA = new FlowBuilder<SimpleFlow>("flowA").start(stepA).build();
Flow flowB = new FlowBuilder<SimpleFlow>("flowB").start(stepB).build();
Flow flowC = new FlowBuilder<SimpleFlow>("flowC").start(stepC).build();
Flow flowD = new FlowBuilder<SimpleFlow>("flowD").start(stepD).build();

Flow splitFlow = new FlowBuilder<SimpleFlow>("splitFlow")
    .split(new SimpleAsyncTaskExecutor())
    .add(flowA, flowB, flowC, flowD)
    .build();

Job job = new JobBuilder("dailyStatsJob", jobRepository)
    .start(splitFlow)
    .end()
    .build();
```

결과는 동일하다 — 4개 작업이 병렬 실행되고 모두 완료되면 다음으로 진행한다.
차이는 Spring Batch 쪽이 **각 Step의 실행 이력(성공/실패, 소요시간, 처리건수)을 자동으로 DB에 기록**한다는 것이다.

---

## 5. Spring Batch가 제공하는 것 vs CoreDisc에 지금 필요한 것

| Spring Batch 기능 | 어떤 문제를 해결하는가 | CoreDisc 현황 | 필요 여부 |
|---|---|---|---|
| **체크포인트 재시작** | 2시간짜리 배치가 1시간 50분에 실패 → 이어서 재개 | 일간 통계: 수초~수분 내 완료. 실패 시 처음부터 재실행해도 무방 | X |
| **실행 이력 DB 기록** | 운영팀이 "어제 배치 몇 건 처리했나" 조회 | 로그로 기록 중. 운영 대시보드 없음 | X |
| **Skip/Retry 정책** | 1만 건 중 3건 실패 시 나머지 계속 처리 | try-catch + continue로 이미 동일하게 구현 | X (이미 해결) |
| **Chunk 트랜잭션** | 대량 데이터를 메모리에 전부 올리지 않고 N건씩 처리 | PageRequest(0, 100) 반복으로 이미 동일하게 구현 | X (이미 해결) |
| **파티셔닝** | 데이터를 범위별로 나눠 멀티스레드/멀티서버 처리 | 유저 10,000명, 단일 스레드로 수분 내 완료 | X |
| **Flow 제어** | Step A 성공 → Step B, 실패 → Step C (보상) | 독립적인 4개 통계 작업. 분기 로직 없음 | X |

**6개 기능 중 0개가 현재 필요하다.** 이미 직접 구현으로 해결했거나, 현재 규모에서 해당 문제 자체가 발생하지 않는다.

---

## 6. 의사 결정

### 채택: @Scheduled + CompletableFuture + 직접 청크 처리

| 근거 | 설명 |
|------|------|
| **인프라 비용 불필요** | 메타데이터 테이블 9개 + 매 chunk마다 context UPDATE 쿼리가 현재 작업에 실질적 가치를 주지 않음 |
| **코드 단순성** | 10줄 for 루프 vs 50줄 Bean 설정. 팀 누구나 즉시 이해 가능 |
| **이미 동등한 처리** | 청크 처리, 에러 스킵, 병렬 실행을 CompletableFuture + PageRequest로 구현 완료 |
| **배치 처리 시간** | 가장 무거운 일간 통계 병렬 실행이 수분 이내. 재시작이 필요한 시간 규모가 아님 |

### Spring Batch가 아니라 "Spring Batch가 해결하는 문제"를 직접 해결했다

Phase 5에서 실제로 한 작업:

```
Spring Batch의 Chunk Processing → Page 기반 청크 반복 + page=0 패턴
Spring Batch의 Skip Policy     → try-catch + failedCount 로깅
Spring Batch의 Parallel Steps  → CompletableFuture.allOf + 전용 ThreadPool
Spring Batch의 Listeners       → System.currentTimeMillis() 타이밍 + 구조화 로그
```

프레임워크를 쓰지 않은 것이지, 프레임워크가 해결하는 문제를 무시한 것이 아니다.

---

## 7. 언제 Spring Batch를 도입할 것인가 — 마이그레이션 트리거

아래 조건 중 하나라도 해당되면 Spring Batch 도입을 재검토한다:

| 트리거 조건 | 이유 |
|------------|------|
| **배치 처리 시간 > 30분** | 실패 시 처음부터 재실행하는 비용이 커짐. 체크포인트 재시작이 필요 |
| **배치 실행 이력 조회 요구** | 운영/CS팀이 "어제 배치 결과" API를 필요로 하면 JobRepository가 자연스러운 해법 |
| **Step 간 조건부 분기** | "A 실패 시 B 대신 C를 실행" 같은 Flow 제어가 필요하면 직접 if-else보다 Spring Batch Flow가 유지보수에 유리 |
| **데이터 처리량 > 100만 건** | 파티셔닝으로 멀티스레드 분산 처리가 필요한 규모 |

현재 CoreDisc의 유저 규모(10,000명)와 배치 복잡도에서는 이 트리거에 해당하지 않는다.

---

## 8. 참고

- Phase 5 배치 최적화 상세: `docs/optimization/07-phase5-batch.md`
- CompletableFuture 병렬 실행 구현: `BatchScheduler.java`
- 청크 기반 삭제 구현: `PostCommandServiceImpl.cleanupOldTempPosts()`
- 배치 쿼리 N+1 제거 구현: `NotificationReminderScheduler.java`
