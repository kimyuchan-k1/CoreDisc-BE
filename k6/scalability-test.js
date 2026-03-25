import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

/**
 * Part B: 피드 확장성 한계 측정
 *
 * 테스트 1: 팔로잉 수별 피드 응답 시간 (feed-by-following)
 * 테스트 2: VU 단계적 증가 (ramp-test)
 *
 * 사용법:
 *   # 테스트 1: 팔로잉 수별 피드 성능
 *   k6 run --env TEST_MODE=feed-by-following k6/scalability-test.js
 *
 *   # 테스트 2: VU 단계적 증가
 *   k6 run --env TEST_MODE=ramp-test k6/scalability-test.js
 */

// ─── Configuration ───────────────────────────────────────
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TEST_PASSWORD = 'testpass123a';
const TEST_MODE = __ENV.TEST_MODE || 'feed-by-following';

const MANIFEST_PATH = __ENV.MANIFEST_PATH || '/tmp/coredisc-seed-v2/manifest.json';
let manifest;
try {
    manifest = JSON.parse(open(MANIFEST_PATH));
} catch (e) {
    manifest = null;
}

// 확장성 테스트 그룹 매니페스트
const SCALE_MANIFEST_PATH = __ENV.SCALE_MANIFEST_PATH || '/tmp/coredisc-seed-v2/scalability-manifest.json';
let scaleManifest;
try {
    scaleManifest = JSON.parse(open(SCALE_MANIFEST_PATH));
} catch (e) {
    scaleManifest = null;
}

// ─── Custom Metrics ──────────────────────────────────────
// 팔로잉 수별 피드 메트릭
const feedAll50 = new Trend('feed_following_50', true);
const feedAll200 = new Trend('feed_following_200', true);
const feedAll500 = new Trend('feed_following_500', true);
const feedAll1000 = new Trend('feed_following_1000', true);
const feedAll2000 = new Trend('feed_following_2000', true);
const feedAll5000 = new Trend('feed_following_5000', true);

const feedGroupMetrics = {
    50: feedAll50,
    200: feedAll200,
    500: feedAll500,
    1000: feedAll1000,
    2000: feedAll2000,
    5000: feedAll5000,
};

// 일반 메트릭
const feedDuration = new Trend('feed_duration', true);
const errorRate = new Rate('error_rate');
const requestCount = new Counter('total_requests');

// ─── Scenarios ───────────────────────────────────────────
const scenarios = {
    'feed-by-following': {
        scenarios: {
            feed_by_following: {
                executor: 'per-vu-iterations',
                vus: 90,            // 6 groups × 15 users
                iterations: 10,     // 각 VU가 10번 피드 조회
                maxDuration: '5m',
            },
        },
        thresholds: {
            feed_duration: ['p(95)<2000'],
            error_rate: ['rate<0.05'],
        },
    },
    'ramp-test': {
        setupTimeout: '180s',
        scenarios: {
            ramp_up: {
                executor: 'ramping-vus',
                startVUs: 0,
                stages: [
                    { duration: '30s', target: 50 },     // warm-up
                    { duration: '2m', target: 50 },      // sustain 50
                    { duration: '30s', target: 100 },    // ramp to 100
                    { duration: '2m', target: 100 },     // sustain 100
                    { duration: '30s', target: 200 },    // ramp to 200
                    { duration: '2m', target: 200 },     // sustain 200
                    { duration: '30s', target: 500 },    // ramp to 500
                    { duration: '2m', target: 500 },     // sustain 500
                    { duration: '1m', target: 0 },       // cool-down
                ],
                gracefulRampDown: '10s',
            },
        },
        thresholds: {
            feed_duration: ['p(95)<2000'],
            error_rate: ['rate<0.10'],  // ramp test는 에러 허용 범위 넓게
        },
    },
};

export const options = scenarios[TEST_MODE] || scenarios['feed-by-following'];

// ─── Setup ───────────────────────────────────────────────
export function setup() {
    console.log(`=== 피드 확장성 테스트: ${TEST_MODE} ===`);

    if (TEST_MODE === 'feed-by-following') {
        return setupFeedByFollowing();
    } else {
        return setupRampTest();
    }
}

function setupFeedByFollowing() {
    if (!scaleManifest) {
        console.log('ERROR: scalability-manifest.json not found.');
        console.log('Run: python3 k6/generate_scalability_data.py first');
        return { users: [], groups: {} };
    }

    const groups = scaleManifest.groups;
    const allUsers = [];
    let loginFailures = 0;

    for (const [groupName, groupData] of Object.entries(groups)) {
        const memberIds = groupData.member_ids;
        let logged = 0;

        for (const memberId of memberIds) {
            const username = `loaduser_${memberId}`;
            const loginRes = http.post(
                `${BASE_URL}/api/auth/login`,
                JSON.stringify({ username, password: TEST_PASSWORD }),
                { headers: { 'Content-Type': 'application/json' } }
            );

            if (loginRes.status === 200) {
                const body = JSON.parse(loginRes.body);
                allUsers.push({
                    group: groupName,
                    followingCount: groupData.following_count,
                    username,
                    memberId: body.result.id,
                    accessToken: body.result.accessToken,
                });
                logged++;
            } else {
                loginFailures++;
            }
        }
        console.log(`  ${groupName} (following=${groupData.following_count}): ${logged}/${memberIds.length} logged in`);
    }

    console.log(`Total: ${allUsers.length} users (${loginFailures} failures)`);
    return { users: allUsers, mode: 'feed-by-following' };
}

function setupRampTest() {
    // ramp test는 기존 manifest의 T2, T3 유저를 사용
    const userIds = [];
    if (manifest) {
        // T2 (500명) + T3 일부 (500명) = 최대 1000명
        userIds.push(...(manifest.tiers.T2?.member_ids || []).slice(0, 300));
        userIds.push(...(manifest.tiers.T3?.member_ids || []).slice(0, 200));
    } else {
        // Fallback
        for (let i = 11; i <= 510; i++) userIds.push(i);
    }

    const users = [];
    let loginFailures = 0;

    // VU 500까지 테스트하므로 최대 500명 로그인
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
    }

    console.log(`Ramp test: ${users.length}/${targetIds.length} logged in (${loginFailures} failures)`);
    return { users, mode: 'ramp-test' };
}

// ─── Default Function ────────────────────────────────────
export default function (data) {
    const { users, mode } = data;
    if (!users || users.length === 0) return;

    if (mode === 'feed-by-following') {
        feedByFollowingTest(data);
    } else {
        rampTest(data);
    }
}

// ─── Test 1: 팔로잉 수별 피드 성능 ──────────────────────
function feedByFollowingTest(data) {
    const { users } = data;
    const user = users[(__VU - 1) % users.length];
    if (!user) return;

    const headers = {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${user.accessToken}`,
    };

    // 피드 조회
    const start = Date.now();
    const res = http.get(
        `${BASE_URL}/api/posts?feedType=ALL&size=10`,
        { headers }
    );
    const duration = Date.now() - start;

    requestCount.add(1);
    feedDuration.add(duration);

    // 그룹별 메트릭 기록
    const metric = feedGroupMetrics[user.followingCount];
    if (metric) {
        metric.add(duration);
    }

    const success = check(res, {
        'feed status 200': (r) => r.status === 200,
    });
    errorRate.add(!success);

    // 페이지네이션 테스트 (2번째 페이지도 조회)
    if (res.status === 200) {
        const body = JSON.parse(res.body);
        const nextCursor = body.result?.nextCursor;
        if (nextCursor) {
            const start2 = Date.now();
            const res2 = http.get(
                `${BASE_URL}/api/posts?feedType=ALL&size=10&lastPostId=${nextCursor}`,
                { headers }
            );
            const duration2 = Date.now() - start2;
            feedDuration.add(duration2);
            if (metric) metric.add(duration2);
        }
    }

    sleep(0.5 + Math.random() * 0.5);  // 0.5~1초 think time
}

// ─── Test 2: VU 단계적 증가 ─────────────────────────────
function rampTest(data) {
    const { users } = data;
    const user = users[(__VU - 1) % users.length];
    if (!user) return;

    const headers = {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${user.accessToken}`,
    };

    // 피드 ALL 조회 (가장 무거운 쿼리)
    const start = Date.now();
    const res = http.get(
        `${BASE_URL}/api/posts?feedType=ALL&size=10`,
        { headers }
    );
    const duration = Date.now() - start;

    requestCount.add(1);
    feedDuration.add(duration);

    const success = check(res, {
        'feed status 200': (r) => r.status === 200,
    });
    errorRate.add(!success);

    // think time
    sleep(0.5 + Math.random() * 1.0);
}

// ─── Teardown ────────────────────────────────────────────
export function teardown(data) {
    console.log(`\n=== 테스트 완료: ${TEST_MODE} ===`);

    if (data.mode === 'feed-by-following') {
        console.log(`\n팔로잉 수별 결과는 k6 메트릭에서 확인:`);
        console.log(`  feed_following_50   → 팔로잉 50명 그룹`);
        console.log(`  feed_following_200  → 팔로잉 200명 그룹`);
        console.log(`  feed_following_500  → 팔로잉 500명 그룹`);
        console.log(`  feed_following_1000 → 팔로잉 1000명 그룹`);
        console.log(`  feed_following_2000 → 팔로잉 2000명 그룹`);
        console.log(`  feed_following_5000 → 팔로잉 5000명 그룹`);
    } else {
        console.log(`\nVU 단계별 결과는 k6 HTML 리포트에서 확인.`);
        console.log(`  k6 run --out json=results.json 으로 실행 후 분석`);
    }

    console.log(`\n※ DB 메트릭 확인:`);
    console.log(`  SHOW STATUS LIKE 'Threads_%';`);
    console.log(`  SHOW STATUS LIKE 'Innodb_row_lock%';`);
}
