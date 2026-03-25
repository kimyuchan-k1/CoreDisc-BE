import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// ─── Custom Metrics ──────────────────────────────────────
const likeDuration = new Trend('like_duration', true);
const followDuration = new Trend('follow_duration', true);
const errorRate = new Rate('error_rate');
const http500Count = new Counter('http_500_count');
const requestCount = new Counter('total_requests');

// ─── Configuration ───────────────────────────────────────
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TEST_PASSWORD = 'testpass123a';
const USER_COUNT = 100;

// ─── Scenarios ───────────────────────────────────────────
export const options = {
    scenarios: {
        notification_stress: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '15s', target: 50 },   // ramp to 50
                { duration: '30s', target: 100 },   // ramp to 100
                { duration: '2m', target: 100 },    // sustain peak
                { duration: '15s', target: 0 },     // cool-down
            ],
            gracefulRampDown: '10s',
        },
    },
    thresholds: {
        http_500_count: ['count<1'],           // 목표: HTTP 500 = 0
        error_rate: ['rate<0.05'],
        http_req_duration: ['p(95)<3000'],
    },
};

// ─── Setup: Login user pool ──────────────────────────────
export function setup() {
    console.log(`Logging in ${USER_COUNT} users for notification stress test...`);

    const userPool = [];
    let failures = 0;

    for (let i = 1; i <= USER_COUNT; i++) {
        const username = `loaduser_${i}`;
        const loginRes = http.post(
            `${BASE_URL}/api/auth/login`,
            JSON.stringify({ username, password: TEST_PASSWORD }),
            { headers: { 'Content-Type': 'application/json' } }
        );

        if (loginRes.status === 200) {
            const body = JSON.parse(loginRes.body);
            userPool.push({
                username,
                memberId: body.result.id,
                accessToken: body.result.accessToken,
            });
        } else {
            failures++;
            if (failures <= 5) {
                console.log(`Login failed for ${username}: ${loginRes.status}`);
            }
        }
    }

    console.log(`Logged in: ${userPool.length}/${USER_COUNT} (${failures} failures)`);
    return { users: userPool };
}

// ─── Main Scenario: 좋아요 + 팔로우 반복 (알림 집중 생성) ────
export default function (data) {
    const { users } = data;
    if (!users || users.length === 0) return;

    const user = users[__VU % users.length];
    const headers = {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${user.accessToken}`,
    };

    // 50% 좋아요, 50% 팔로우
    if (Math.random() < 0.5) {
        stressLike(headers);
    } else {
        stressFollow(headers, user);
    }

    // 최소 think time — 최대 부하
    sleep(0.1);
}

// ─── 좋아요 스트레스 (알림 생성) ─────────────────────────
function stressLike(headers) {
    group('StressLike', () => {
        // 피드에서 게시글 조회
        const feedRes = http.get(`${BASE_URL}/api/posts?feedType=ALL&size=5`, {
            headers,
            tags: { name: 'GET /api/posts' },
        });
        requestCount.add(1);

        if (feedRes.status === 200) {
            try {
                const body = JSON.parse(feedRes.body);
                const posts = body.result && body.result.posts;
                if (posts && posts.length > 0) {
                    const post = posts[Math.floor(Math.random() * posts.length)];

                    // 좋아요 → 알림 생성
                    const likeRes = http.post(
                        `${BASE_URL}/api/posts/${post.postId}/likes`,
                        null,
                        { headers, tags: { name: 'POST /api/posts/{postId}/likes' } }
                    );
                    requestCount.add(1);
                    likeDuration.add(likeRes.timings.duration);

                    const ok = check(likeRes, {
                        'like not 500': (r) => r.status !== 500,
                    });
                    errorRate.add(!ok);
                    if (likeRes.status === 500) http500Count.add(1);

                    sleep(0.2);

                    // 좋아요 취소 (데이터 정리)
                    const unlikeRes = http.del(
                        `${BASE_URL}/api/posts/${post.postId}/likes`,
                        null,
                        { headers, tags: { name: 'DELETE /api/posts/{postId}/likes' } }
                    );
                    requestCount.add(1);
                    if (unlikeRes.status === 500) http500Count.add(1);
                }
            } catch (_) {}
        } else {
            if (feedRes.status === 500) http500Count.add(1);
            errorRate.add(true);
        }
    });
}

// ─── 팔로우 스트레스 (알림 생성) ─────────────────────────
function stressFollow(headers, user) {
    group('StressFollow', () => {
        // 랜덤 유저 팔로우 → 알림 생성
        const targetId = Math.floor(Math.random() * 500) + 1;
        if (targetId === user.memberId) return;

        const followRes = http.post(
            `${BASE_URL}/api/follows/${targetId}`,
            null,
            { headers, tags: { name: 'POST /api/follows/{memberId}' } }
        );
        requestCount.add(1);
        followDuration.add(followRes.timings.duration);

        const ok = check(followRes, {
            'follow not 500': (r) => r.status !== 500,
        });
        errorRate.add(!ok && followRes.status !== 409);
        if (followRes.status === 500) http500Count.add(1);

        sleep(0.3);

        // 언팔로우 (데이터 정리)
        const unfollowRes = http.del(
            `${BASE_URL}/api/follows/${targetId}`,
            null,
            { headers, tags: { name: 'DELETE /api/follows/{memberId}' } }
        );
        requestCount.add(1);
        if (unfollowRes.status === 500) http500Count.add(1);
    });
}

// ─── Teardown ────────────────────────────────────────────
export function teardown(data) {
    console.log(`Notification stress test completed. ${data.users.length} users used.`);
    console.log('Check Prometheus: executor_queued_tasks{name="notificationExecutor"}');
    console.log('Check Prometheus: notification.send.success / notification.send.failure');
    console.log('Check Prometheus: fcm.circuit.state');
}
