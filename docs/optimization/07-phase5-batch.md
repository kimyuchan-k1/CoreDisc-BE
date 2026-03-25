# Phase 5: 배치 최적화

> 완료일: 2026-02-20

## 작업 범위
배치/스케줄러 작업의 메모리 효율성, 실행 속도, 쿼리 효율성을 개선.

---

## 5-1. 임시 게시글 정리 청크 기반 처리

### Before
- `cleanupOldTempPosts()`가 `findAllByStatusAndCreatedAtBefore()`로 모든 TEMP 게시글을 한번에 메모리 로딩
- 대량 데이터 시 OOM 위험
- 실패 시 전체 작업 중단 (`throw new PostHandler`)

### After
- 100건 단위 Page 기반 청크 처리
- 삭제 후 데이터가 당겨오므로 항상 `page=0`으로 조회
- 실패 건은 로그 남기고 스킵, 나머지 계속 처리

### 구현
```java
final int CHUNK_SIZE = 100;
Page<Post> postPage;
do {
    postPage = postRepository.findTempPostsPageable(
            PostStatus.TEMP, cutoffDate.plusDays(1).atStartOfDay(),
            PageRequest.of(0, CHUNK_SIZE));
    for (Post post : postPage.getContent()) {
        try {
            // S3 이미지 삭제 + DB 삭제
            postRepository.delete(post);
            deletedCount++;
        } catch (Exception e) {
            failedCount++;
            log.error("TEMP 게시글 삭제 실패 - ID: {}", post.getId());
        }
    }
} while (postPage.hasNext());
```

### 변경 파일
| 파일 | 작업 |
|------|------|
| `JpaPostRepository.java` | 페이지네이션 메서드 추가 |
| `PostRepository.java` (도메인) | `findTempPostsPageable` 추가 |
| `PostRepositoryAdaptor.java` | 구현 추가 |
| `PostCommandServiceImpl.java` | 청크 기반 처리로 변경 |

---

## 5-2. 일일 배치 스케줄러 병렬 실행

### Before
```
[자정 00:00]
  → generateDailyStatistics()        // 순차 1
  → generateMonthlyFixedQuestionStats() // 순차 2
  → generateRandomQuestionsStats()    // 순차 3
  → generateMonthlySelectionDiaryStats() // 순차 4
  → cleanupOldTempPosts()            // 순차 5
총 소요: T1 + T2 + T3 + T4 + T5
```

### After
```
[자정 00:00]
  → CompletableFuture.allOf(
       generateDailyStatistics(),           // 병렬
       generateMonthlyFixedQuestionStats(),  // 병렬
       generateRandomQuestionsStats(),       // 병렬
       generateMonthlySelectionDiaryStats()  // 병렬
     ).join()
  → cleanupOldTempPosts()                   // 순차 (통계 완료 후)
총 소요: max(T1, T2, T3, T4) + T5
```

### 구현
- `AsyncConfig`에 `batchExecutor` 스레드풀 추가 (core:4, max:8, queue:20)
- `BatchScheduler`에서 `@Qualifier("batchExecutor")` 주입
- `CompletableFuture.runAsync(task, batchExecutor)` + `.exceptionally()` 에러 핸들링
- `CompletableFuture.allOf(...).join()`으로 모든 작업 완료 대기

### 변경 파일
| 파일 | 작업 |
|------|------|
| `AsyncConfig.java` | `batchExecutor` 스레드풀 빈 추가 |
| `BatchScheduler.java` | CompletableFuture 병렬 실행으로 변경 |

---

## 5-3. 리마인더 스케줄러 N+1 쿼리 최적화

### Before (멤버당 N+1 쿼리)
```
5분마다 실행:
  → dailyTargets (N명) 조회
  → 각 멤버에 대해:
      hasTodayQuestions()         → 4개 개별 쿼리 (order 1,2,3,4)
      hasUnansweredQuestions()    → 4개 질문 쿼리 + 4개 답변 쿼리
  총 쿼리: N × (4 + 8) = N × 12 쿼리
  50명 매칭 시: ~600 쿼리
```

### After (전체 배치 쿼리)
```
5분마다 실행:
  → 모든 대상 멤버 ID 수집
  → TodayQuestion 배치 쿼리 1회 (@EntityGraph)
  → PostAnswer 답변 순서 배치 쿼리 1회 (JPQL)
  → 메모리 Map 구성 (memberId → Set<questionOrder>, memberId → Set<answerOrder>)
  → 각 멤버: Map.get()으로 O(1) 조회
  총 쿼리: 2 쿼리 (멤버 수와 무관)
```

### 구현

**TodayQuestion 배치 조회:**
기존 `findByMemberIdInAndQuestionOrderInAndSelectedDateBetween` 메서드 재활용 (Phase 2에서 생성, @EntityGraph 포함)

**PostAnswer 배치 조회:**
```sql
SELECT DISTINCT pa.post.member.id, pa.answerOrder
FROM PostAnswer pa
WHERE pa.post.member.id IN :memberIds
  AND pa.post.createdAt >= :start
  AND pa.post.createdAt < :end
```

**메모리 룩업:**
```java
// 질문 완성 여부: O(1)
boolean hasAllQuestions = questionMap.getOrDefault(memberId, Set.of())
    .containsAll(Set.of(1, 2, 3, 4));

// 답변 완성 여부: O(1)
boolean hasAllAnswers = answerMap.getOrDefault(memberId, Set.of())
    .containsAll(Set.of(1, 2, 3, 4));
```

### 변경 파일
| 파일 | 작업 |
|------|------|
| `NotificationReminderScheduler.java` | 전체 리팩토링 - 배치 쿼리 기반 |
| `JpaPostAnswerRepository.java` | 배치 답변 순서 조회 JPQL 추가 |
| `PostAnswerRepository.java` (도메인) | 배치 메서드 추가 |
| `PostAnswerRepositoryAdaptor.java` | 구현 추가 |

---

## 쿼리 수 개선 요약

| 대상 | Before | After | 감소율 |
|------|--------|-------|--------|
| 리마인더 (50명 매칭) | ~600 쿼리 | **2 쿼리** | **-99.7%** |
| 일일 배치 실행 시간 | T1+T2+T3+T4 | max(T1,T2,T3,T4) | ~75% 단축 |
| TEMP 게시글 메모리 | 전체 로딩 | 100건 청크 | OOM 방지 |

---

## 설계 결정

### 왜 Spring Batch를 쓰지 않았는가?

Spring Batch의 체크포인트 재시작, 실행 이력 DB 기록, Skip/Retry 정책, 파티셔닝 등의 기능을 검토했으나, 현재 CoreDisc의 배치 규모(수분 이내 완료, 유저 10,000명)에서는 해당 기능이 해결하는 문제가 발생하지 않는다. Spring Batch 도입 시 메타데이터 테이블 9개와 매 chunk마다 ExecutionContext UPDATE 쿼리가 추가되는데, 이 비용이 실질적 가치 없이 발생한다.

대신 Spring Batch가 해결하는 문제(청크 처리, 에러 스킵, 병렬 실행)를 CompletableFuture + PageRequest + try-catch로 직접 구현했다.

상세 기술 비교 및 의사결정 근거: [`docs/optimization/09-adr-batch-framework.md`](./09-adr-batch-framework.md)

### 왜 cleanupOldTempPosts에서 page=0을 반복하는가?
- 삭제 작업이므로 매 청크 처리 후 데이터가 줄어듦
- page=1로 가면 데이터가 당겨져서 일부 건을 건너뛸 수 있음
- 항상 page=0으로 조회하면 남은 데이터의 첫 100건을 정확히 가져옴
