# 남은 보완 체크리스트

> 캐시 수정 외 나머지 작업 항목

---

## Phase 5 배치 최적화 정량 검증 보강

### 문서 반영 대상: `docs/portfolio/feed-comment-optimization.md` Phase 5 섹션

- [x] **리마인더 스케줄러 실행 시간 Before/After 측정**
  - 방법: 스케줄러 실행 전후 로그 타임스탬프 비교 또는 `System.currentTimeMillis()` 차이 출력
  - 50명 매칭 기준으로 측정하면 됨
  - 쿼리 수 ~600→2는 이미 있으니, 실행 시간(ms)을 추가하면 설득력이 훨씬 강해짐

- [x] **배치 병렬 실행 시간 Before/After 측정**
  - 4개 통계 작업의 순차 실행 총 시간 vs 병렬 실행 총 시간
  - CompletableFuture.allOf() 적용 전후 비교

- [x] **임시 게시글 청크 처리 — 메모리 사용량 비교** (선택사항)
  - 측정이 어려우면 "OOM 방지" 정도로 정성적 기술도 OK
