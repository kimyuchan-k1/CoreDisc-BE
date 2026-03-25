# CoreDisc 트러블슈팅 & 최적화 기록

부하 테스트 및 성능 최적화 과정에서 발견하고 해결한 이슈들을 기록합니다.

## 문서 목록

| 문서 | 설명 | 상태 |
|------|------|------|
| [01-load-test-setup.md](./01-load-test-setup.md) | 부하 테스트 환경 구축 과정 | 완료 |
| [02-today-question-non-unique-query.md](./02-today-question-non-unique-query.md) | TodayQuestion 쿼리 non-unique result 에러 수정 | 완료 |
| [03-known-issues.md](./03-known-issues.md) | 발견했으나 미수정인 알려진 이슈 | 진행중 |

## 부하 테스트 결과 요약

| 테스트 | 에러율 | p95 응답시간 | 비고 |
|--------|--------|-------------|------|
| 1차 (빈 DB) | 0% | ~11ms | 데이터 없어 비현실적 |
| 2차 (918MB 시드, 수정 전) | 44.28% | ~35ms | 다수 API 에러 |
| 3차 (테스트 수정 후) | 10.92% | ~224ms | CORE 피드 에러 잔존 |
| 4차 (CORE 피드 제외) | 7.75% | ~481ms | 임계값 통과, 피드 77.7% 성공 |
| 5차 (TodayQuestion 버그 수정) | **0.01%** | **~518ms** | 모든 체크 100% 통과 |
