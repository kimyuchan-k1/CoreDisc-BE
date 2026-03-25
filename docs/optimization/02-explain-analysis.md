# 02. EXPLAIN 분석 결과 (Phase 0-4)

## 현재 인덱스 현황

모든 테이블에 **PK + FK 자동 인덱스만 존재**. 커스텀 복합 인덱스 0개.

---

## EXPLAIN 결과 요약

| # | 쿼리 | type | rows | filtered | Extra | 문제점 |
|---|-------|------|------|----------|-------|--------|
| 1 | 피드 조회 (post + follow 서브쿼리) | index | 11 | 50% | Backward index scan | 서브쿼리 매번 실행 |
| 2 | 팔로잉 ID 조회 | ref | 17 | 100% | - | FK 인덱스 사용, 양호 |
| 3 | 팔로잉+서클 조회 | ref | 17 | **50%** | Using where | is_circle 필터링 비효율 |
| 4 | **TodayQuestion** | ref | **212** | **1.11%** | Using where | **member_id만으로 212행 스캔** |
| 5 | **알림 목록** | ref | 79 | 100% | **Using filesort** | **created_at 정렬에 인덱스 없음** |
| 6 | 댓글 조회 | ref | 1 | 10% | Backward index scan | post_id만으로, depth 필터 비효율 |
| 7 | 좋아요 존재 확인 | - | - | - | no matching row | uk_post_member 사용, 양호 |
| 8 | **멤버 검색 (LIKE)** | **ALL** | **9,940** | **20.99%** | Using where | **Full Table Scan!** |
| 9 | 팔로워 목록 | ref+eq_ref | 25+1 | 100% | - | FK 인덱스 사용, 양호 |
| 10 | 답변 배치 조회 | range | 15 | 100% | Using index condition | UK 사용, 양호 |
| 11 | 내 포스트 (member+status) | ref | 20 | 50% | Backward index scan | member_id만 사용, status 필터 비효율 |
| 12 | 디바이스 조회 | ref | 1 | 50% | Using where | member_id만, is_active 필터 비효율 |

---

## 심각도별 분류

### 즉시 수정 필요

#### 1. TodayQuestion — rows: 212, filtered: 1.11%
```
현재: member_id FK 인덱스만 사용 → 212행 스캔 후 question_order, selected_date로 필터
필요: (member_id, question_order, selected_date) 복합 인덱스
기대: rows 212 → 1~2, filtered 100%
```
**피드 조회에서 게시글당 1회 호출 → 10건 피드면 10×212행 스캔 = 2,120행 불필요 스캔**

#### 2. 알림 목록 — Using filesort
```
현재: receiver_id FK 인덱스로 79행 찾은 뒤, 메모리에서 created_at 정렬 (filesort)
필요: (receiver_id, created_at DESC) 복합 인덱스
기대: filesort 제거, 인덱스 순서로 정렬 완료
```

#### 3. 멤버 검색 — Full Table Scan (ALL), rows: 9,940
```
현재: username LIKE '%load%' → LIKE 앞에 %가 있어 인덱스 사용 불가
필요: Full-Text Index 또는 앱 레벨 검색 최적화
참고: LIKE 'load%'로 바꾸면 인덱스 사용 가능하나 비즈니스 요구 확인 필요
```

### 개선 권장

#### 4. 팔로우+서클 — filtered: 50%
```
현재: follower_id FK 인덱스만 → 17행 스캔 후 is_circle로 필터 (50% 낭비)
필요: (follower_id, is_circle) 복합 인덱스
기대: filtered 100%, 서클만 정확히 조회
```

#### 5. post (member+status) — filtered: 50%
```
현재: member_id FK 인덱스만 → 20행 스캔 후 status='PUBLISHED'로 필터
필요: (member_id, status) 복합 인덱스
기대: filtered 100%
```

#### 6. 댓글 (post_id+depth) — filtered: 10%
```
현재: post_id FK 인덱스만 → depth=0 필터링은 메모리에서
필요: (post_id, depth, id) 복합 인덱스
기대: depth=0인 부모 댓글만 정확히 조회
```

#### 7. device (member_id+is_active) — filtered: 50%
```
현재: member_id FK 인덱스만 → is_active 필터 메모리에서
필요: (member_id, is_active) 복합 인덱스
```

### 양호 (인덱스 변경 불필요)

- 팔로잉 ID 조회: FK 인덱스로 충분 (rows: 17, filtered: 100%)
- 좋아요 존재 확인: UNIQUE KEY 사용
- 답변 배치 조회: UNIQUE KEY 사용
- 팔로워 목록: FK 인덱스 + PK JOIN, 효율적

---

## Phase 1에서 추가할 인덱스 계획

| 테이블 | 인덱스 | 컬럼 | 해결하는 문제 |
|--------|--------|------|-------------|
| today_question | idx_tq_member_order_date | (member_id, question_order, selected_date) | rows 212→1, filtered 1%→100% |
| notification | idx_noti_receiver_created | (receiver_id, created_at) | filesort 제거 |
| follow | idx_follow_follower_circle | (follower_id, is_circle) | filtered 50%→100% |
| post | idx_post_member_status | (member_id, status) | filtered 50%→100% |
| comment | idx_comment_post_depth_id | (post_id, depth, id) | filtered 10%→100% |
| device | idx_device_member_active | (member_id, is_active) | filtered 50%→100% |

---

## 다음 단계

Phase 1-1에서 위 인덱스를 JPA 엔티티 `@Index` 어노테이션으로 추가하고, EXPLAIN 재실행하여 효과 확인.
