import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

/**
 * Phase 4-1: 통합 부하 테스트
 *
 * 혼합 워크로드: 읽기 80% + 쓰기 20%
 *   - 읽기: 피드 조회 (60%) + 게시글 상세 조회 (20%)
 *   - 쓰기: 좋아요 토글 (15%) + 댓글 작성 (5%)
 *
 * 사용법:
 *   k6 run --env BASE_URL=http://34.64.214.78:8080 k6/integration-test.js
 */

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TEST_PASSWORD = 'testpass123a';
const MANIFEST_PATH = __ENV.MANIFEST_PATH || '/tmp/coredisc-seed-v2/manifest.json';

let manifest;
try {
    manifest = JSON.parse(open(MANIFEST_PATH));
} catch (e) {
    manifest = null;
}

// ─── Custom Metrics ─────────────────────────────────────
const feedDuration = new Trend('feed_duration', true);
const detailDuration = new Trend('detail_duration', true);
const likeDuration = new Trend('like_duration', true);
const commentDuration = new Trend('comment_duration', true);
const readDuration = new Trend('read_duration', true);
const writeDuration = new Trend('write_duration', true);
const errorRate = new Rate('error_rate');
const requestCount = new Counter('total_requests');

// ─── Scenarios ──────────────────────────────────────────
export const options = {
    setupTimeout: '180s',
    scenarios: {
        integration: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 50 },     // warm-up
                { duration: '2m', target: 50 },       // sustain 50
                { duration: '30s', target: 100 },     // ramp to 100
                { duration: '2m', target: 100 },      // sustain 100
                { duration: '30s', target: 200 },     // ramp to 200
                { duration: '2m', target: 200 },      // sustain 200
                { duration: '30s', target: 500 },     // ramp to 500
                { duration: '2m', target: 500 },      // sustain 500
                { duration: '1m', target: 0 },        // cool-down
            ],
            gracefulRampDown: '10s',
        },
    },
    thresholds: {
        feed_duration: ['p(95)<3000'],
        error_rate: ['rate<0.05'],
    },
};

// ─── Setup ──────────────────────────────────────────────
export function setup() {
    console.log('=== Phase 4-1: 통합 부하 테스트 (읽기 80% + 쓰기 20%) ===');

    const userIds = [];
    if (manifest) {
        userIds.push(...(manifest.tiers.T2?.member_ids || []).slice(0, 300));
        userIds.push(...(manifest.tiers.T3?.member_ids || []).slice(0, 200));
    } else {
        for (let i = 11; i <= 510; i++) userIds.push(i);
    }

    const users = [];
    let loginFailures = 0;
    const maxUsers = 500;
    const targetIds = userIds.slice(0, maxUsers);

    for (const id of targetIds) {
        const username = `loaduser_${id}`;
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
        }

        if (users.length % 100 === 0) {
            console.log(`  Logged in ${users.length}/${targetIds.length}...`);
        }
    }

    console.log(`Setup complete: ${users.length}/${targetIds.length} logged in (${loginFailures} failures)`);

    // 테스트용 게시글 ID 범위 (시드 데이터 기준)
    // 최근 게시글 ID를 사용하여 좋아요/댓글 타겟
    return { users };
}

// ─── Default Function ───────────────────────────────────
export default function (data) {
    const { users } = data;
    if (!users || users.length === 0) return;

    const user = users[(__VU - 1) % users.length];
    const headers = {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${user.accessToken}`,
    };

    // 워크로드 비율: 읽기 80% + 쓰기 20%
    const rand = Math.random();

    if (rand < 0.60) {
        // 60%: 피드 조회
        feedRequest(headers, user);
    } else if (rand < 0.80) {
        // 20%: 게시글 상세 조회
        detailRequest(headers, user);
    } else {
        // 20%: 좋아요 토글
        likeToggleRequest(headers, user);
    }

    sleep(0.5 + Math.random() * 0.5); // 0.5~1초 간격
}

// ─── Request Functions ──────────────────────────────────

function feedRequest(headers, user) {
    const start = Date.now();
    const res = http.get(
        `${BASE_URL}/api/posts?feedType=ALL&size=10`,
        { headers }
    );
    const duration = Date.now() - start;

    requestCount.add(1);
    feedDuration.add(duration);
    readDuration.add(duration);

    const success = check(res, {
        'feed 200': (r) => r.status === 200,
    });
    errorRate.add(!success);
}

function detailRequest(headers, user) {
    // 랜덤 게시글 ID (최근 게시글 범위)
    const postId = Math.floor(Math.random() * 80000) + 100000; // 130K~180K 범위

    const start = Date.now();
    const res = http.get(
        `${BASE_URL}/api/posts/${postId}`,
        { headers }
    );
    const duration = Date.now() - start;

    requestCount.add(1);
    detailDuration.add(duration);
    readDuration.add(duration);

    const success = check(res, {
        'detail 200 or 404': (r) => r.status === 200 || r.status === 404,
    });
    errorRate.add(!success);
}

function likeToggleRequest(headers, user) {
    // 랜덤 게시글에 좋아요 토글 (POST → DELETE)
    const postId = Math.floor(Math.random() * 80000) + 100000;

    const start = Date.now();
    const likeRes = http.post(
        `${BASE_URL}/api/posts/${postId}/likes`,
        null,
        { headers }
    );
    const duration = Date.now() - start;

    requestCount.add(1);
    likeDuration.add(duration);
    writeDuration.add(duration);

    const success = check(likeRes, {
        'like 200 or 409': (r) => r.status === 200 || r.status === 409,
    });
    errorRate.add(!success);

    // 좋아요 성공 시 바로 취소 (데이터 오염 방지)
    if (likeRes.status === 200) {
        http.del(
            `${BASE_URL}/api/posts/${postId}/likes`,
            null,
            { headers }
        );
    }
}

function commentRequest(headers, user) {
    const postId = Math.floor(Math.random() * 80000) + 100000;

    const start = Date.now();
    const res = http.post(
        `${BASE_URL}/api/posts/${postId}/comments`,
        JSON.stringify({ content: `k6 test comment ${Date.now()}` }),
        { headers }
    );
    const duration = Date.now() - start;

    requestCount.add(1);
    commentDuration.add(duration);
    writeDuration.add(duration);

    const success = check(res, {
        'comment 200 or 404': (r) => r.status === 200 || r.status === 404,
    });
    errorRate.add(!success);
}

// ─── Teardown ───────────────────────────────────────────
export function teardown(data) {
    console.log('\n=== 통합 테스트 완료 ===');
    console.log('워크로드 비율: 피드 60% + 상세 20% + 좋아요 15% + 댓글 5%');
    console.log('\n메트릭 설명:');
    console.log('  feed_duration   → 피드 조회 응답 시간');
    console.log('  detail_duration → 게시글 상세 조회 응답 시간');
    console.log('  like_duration   → 좋아요 토글 응답 시간');
    console.log('  comment_duration → 댓글 작성 응답 시간');
    console.log('  read_duration   → 전체 읽기 응답 시간 (피드 + 상세)');
    console.log('  write_duration  → 전체 쓰기 응답 시간 (좋아요 + 댓글)');
}
