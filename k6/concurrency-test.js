import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

/**
 * Part A: 동시 좋아요 정합성 테스트
 *
 * 목적: 동일 Post에 N명이 동시 좋아요 시 likeCount 정합성 검증
 * 가설: "JPA Dirty Checking 기반 카운트 증가는 동시 요청 시 Lost Update가 발생할 것이다"
 *
 * 사용법:
 *   k6 run --env TARGET_POST_ID=1 --env VUS=50 k6/concurrency-test.js
 *
 * 테스트 흐름:
 *   1. setup: N명의 유저 로그인
 *   2. default: 모든 VU가 동시에 같은 Post에 좋아요
 *   3. teardown: 실제 좋아요 수 vs likeCount 비교 → 정합성 검증
 */

// ─── Configuration ───────────────────────────────────────
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TEST_PASSWORD = 'testpass123a';
const TARGET_POST_ID = parseInt(__ENV.TARGET_POST_ID || '1');
const VUS = parseInt(__ENV.VUS || '50');

// 테스트 유저 범위: T3 유저 (511~5010) 중에서 사용
// 기존 좋아요가 없는 깨끗한 유저를 사용하기 위해 높은 번호대 선택
const USER_ID_START = parseInt(__ENV.USER_ID_START || '4000');

// ─── Custom Metrics ──────────────────────────────────────
const likeSuccessCount = new Counter('like_success_count');
const likeFailCount = new Counter('like_fail_count');
const likeDuplicateCount = new Counter('like_duplicate_count');
const likeResponseTime = new Trend('like_response_time', true);
const likeErrorRate = new Rate('like_error_rate');

// ─── Scenarios ───────────────────────────────────────────
export const options = {
    setupTimeout: '180s',
    scenarios: {
        concurrent_likes: {
            executor: 'per-vu-iterations',
            vus: VUS,
            iterations: 1,  // 각 VU가 정확히 1번만 좋아요
            maxDuration: '60s',
        },
    },
    thresholds: {
        like_error_rate: ['rate<0.05'],  // 에러율 5% 미만
    },
};

// ─── Setup: Login test users ─────────────────────────────
export function setup() {
    console.log(`=== 동시 좋아요 정합성 테스트 ===`);
    console.log(`Target Post ID: ${TARGET_POST_ID}`);
    console.log(`Concurrent VUs: ${VUS}`);
    console.log(`User ID range: ${USER_ID_START} ~ ${USER_ID_START + VUS - 1}`);

    const users = [];
    let loginFailures = 0;

    for (let i = 0; i < VUS; i++) {
        const userId = USER_ID_START + i;
        const username = `loaduser_${userId}`;

        const loginRes = http.post(
            `${BASE_URL}/api/auth/login`,
            JSON.stringify({ username, password: TEST_PASSWORD }),
            { headers: { 'Content-Type': 'application/json' } }
        );

        if (loginRes.status === 200) {
            const body = JSON.parse(loginRes.body);
            users.push({
                username,
                memberId: body.result.id,
                accessToken: body.result.accessToken,
            });
        } else {
            loginFailures++;
            if (loginFailures <= 3) {
                console.log(`Login failed: ${username} → ${loginRes.status}`);
            }
        }
    }

    console.log(`Logged in: ${users.length}/${VUS} (${loginFailures} failures)`);

    // 테스트 전 현재 likeCount 기록
    if (users.length > 0) {
        const postRes = http.get(
            `${BASE_URL}/api/posts/${TARGET_POST_ID}`,
            { headers: { Authorization: `Bearer ${users[0].accessToken}` } }
        );

        if (postRes.status === 200) {
            const post = JSON.parse(postRes.body);
            console.log(`Before: likeCount = ${post.result?.likeCount ?? 'N/A'}`);
        }
    }

    return { users };
}

// ─── Default: 동시 좋아요 실행 ───────────────────────────
export default function (data) {
    const { users } = data;
    if (!users || users.length === 0) return;

    const user = users[__VU - 1];  // VU는 1-based
    if (!user) return;

    const headers = {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${user.accessToken}`,
    };

    // 동시 좋아요 요청
    const start = Date.now();
    const res = http.post(
        `${BASE_URL}/api/posts/${TARGET_POST_ID}/likes`,
        null,
        { headers }
    );
    const duration = Date.now() - start;

    likeResponseTime.add(duration);

    if (res.status === 200 || res.status === 201) {
        likeSuccessCount.add(1);
        likeErrorRate.add(false);
    } else if (res.status === 409 || (res.body && res.body.includes('DUPLICATED'))) {
        // 중복 좋아요 (UNIQUE 제약)
        likeDuplicateCount.add(1);
        likeErrorRate.add(false);  // 중복은 에러가 아님
    } else {
        likeFailCount.add(1);
        likeErrorRate.add(true);
        if (__VU <= 5) {
            console.log(`VU ${__VU} fail: status=${res.status}, body=${res.body?.substring(0, 300)}`);
        }
    }
}

// ─── Teardown: 정합성 검증 ───────────────────────────────
export function teardown(data) {
    const { users } = data;
    if (!users || users.length === 0) return;

    // 잠시 대기 (비동기 이벤트 처리 완료 대기)
    sleep(5);

    const headers = {
        Authorization: `Bearer ${users[0].accessToken}`,
    };

    // Post 상세 조회하여 likeCount 확인
    const postRes = http.get(
        `${BASE_URL}/api/posts/${TARGET_POST_ID}`,
        { headers }
    );

    if (postRes.status === 200) {
        const post = JSON.parse(postRes.body);
        const reportedLikeCount = post.result?.likeCount ?? -1;

        console.log(`\n=== 정합성 검증 결과 ===`);
        console.log(`After: likeCount (Post 필드) = ${reportedLikeCount}`);
        console.log(`Expected: 좋아요 성공 수 (k6 메트릭에서 확인)`);
        console.log(`\n※ likeCount와 실제 좋아요 수가 다르면 Lost Update 발생`);
        console.log(`※ DB에서 직접 확인: SELECT COUNT(*) FROM post_like WHERE post_id = ${TARGET_POST_ID}`);
        console.log(`※ DB에서 직접 확인: SELECT like_count FROM post WHERE id = ${TARGET_POST_ID}`);
    }

    // 테스트 후 좋아요 정리 (다음 테스트를 위해)
    console.log(`\n좋아요 정리 중...`);
    let cleaned = 0;
    for (const user of users) {
        const delRes = http.del(
            `${BASE_URL}/api/posts/${TARGET_POST_ID}/likes`,
            null,
            { headers: { Authorization: `Bearer ${user.accessToken}` } }
        );
        if (delRes.status === 200) cleaned++;
    }
    console.log(`정리 완료: ${cleaned}/${users.length} 좋아요 삭제`);
}
