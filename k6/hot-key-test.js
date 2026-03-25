import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

/**
 * Hot Key Stress Test
 *
 * 100 VUs simultaneously hammering the same 3 "hot" posts for 2 minutes.
 * Measures lock contention and cache effectiveness under extreme concurrency.
 */

// ─── Custom Metrics ──────────────────────────────────────
const hotDetailDuration = new Trend('hot_detail_duration', true);
const hotLikeDuration = new Trend('hot_like_duration', true);
const hotCommentsDuration = new Trend('hot_comments_duration', true);
const errorRate = new Rate('error_rate');
const requestCount = new Counter('total_requests');

// ─── Configuration ───────────────────────────────────────
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TEST_PASSWORD = 'testpass123a';
const MANIFEST_PATH = __ENV.MANIFEST_PATH || '/tmp/coredisc-seed-v2/manifest.json';

let HOT_POST_IDS;
try {
    const manifest = JSON.parse(open(MANIFEST_PATH));
    HOT_POST_IDS = manifest.hot_post_ids.slice(0, 3);
} catch (e) {
    // Fallback: use first 3 post IDs
    HOT_POST_IDS = [1, 2, 3];
}

const NUM_USERS = 100;

// ─── Options ─────────────────────────────────────────────
export const options = {
    scenarios: {
        hot_key_stress: {
            executor: 'constant-vus',
            vus: 100,
            duration: '2m',
        },
    },
    thresholds: {
        hot_detail_duration: ['p(95)<1000', 'p(99)<2000'],
        hot_like_duration: ['p(95)<1500', 'p(99)<3000'],
        error_rate: ['rate<0.1'],
    },
};

// ─── Setup ───────────────────────────────────────────────
export function setup() {
    console.log(`Hot Key Stress Test: ${HOT_POST_IDS.length} posts, ${NUM_USERS} users`);
    console.log(`Target posts: ${HOT_POST_IDS.join(', ')}`);

    const users = [];
    let failures = 0;

    for (let i = 1; i <= NUM_USERS; i++) {
        const username = `loaduser_${i}`;
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
            failures++;
        }
    }

    console.log(`Logged in ${users.length}/${NUM_USERS} users (${failures} failures)`);
    return { users };
}

// ─── Main Test ───────────────────────────────────────────
export default function (data) {
    const { users } = data;
    if (!users || users.length === 0) return;

    const user = users[__VU % users.length];
    const headers = {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${user.accessToken}`,
    };

    // Pick one of the 3 hot posts
    const postId = HOT_POST_IDS[Math.floor(Math.random() * HOT_POST_IDS.length)];

    // 1. GET post detail
    const detailRes = http.get(`${BASE_URL}/api/posts/${postId}`, {
        headers,
        tags: { name: `GET /api/posts/${postId} (hot)` },
    });
    requestCount.add(1);
    hotDetailDuration.add(detailRes.timings.duration);
    const detailOk = check(detailRes, {
        'hot post detail 200': (r) => r.status === 200,
    });
    errorRate.add(!detailOk);

    sleep(0.1);

    // 2. POST like (concurrent contention point)
    const likeRes = http.post(`${BASE_URL}/api/posts/${postId}/likes`, null, {
        headers,
        tags: { name: `POST /api/posts/${postId}/likes (hot)` },
    });
    requestCount.add(1);
    hotLikeDuration.add(likeRes.timings.duration);
    errorRate.add(likeRes.status !== 200 && likeRes.status !== 409);

    sleep(0.1);

    // 3. GET comments (NOTE: CommentController path bug)
    const commentsRes = http.get(`${BASE_URL}/posts/${postId}/comments?size=20`, {
        headers,
        tags: { name: `GET /posts/${postId}/comments (hot)` },
    });
    requestCount.add(1);
    hotCommentsDuration.add(commentsRes.timings.duration);
    errorRate.add(commentsRes.status !== 200);

    sleep(0.1);

    // 4. DELETE like (cleanup for next iteration)
    const unlikeRes = http.del(`${BASE_URL}/api/posts/${postId}/likes`, null, {
        headers,
        tags: { name: `DELETE /api/posts/${postId}/likes (hot)` },
    });
    requestCount.add(1);
    errorRate.add(unlikeRes.status !== 200 && unlikeRes.status !== 404);

    sleep(0.2);
}

// ─── Teardown ────────────────────────────────────────────
export function teardown(data) {
    console.log(`Hot key stress test completed. ${data.users.length} users used.`);
    console.log(`Target posts: ${HOT_POST_IDS.join(', ')}`);
}
