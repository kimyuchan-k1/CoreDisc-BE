import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// ─── 커스텀 메트릭 ────────────────────────────────────────
const feedDuration = new Trend('feed_duration', true);
const postDetailDuration = new Trend('post_detail_duration', true);
const likeDuration = new Trend('like_duration', true);
const profileDuration = new Trend('profile_duration', true);
const notificationDuration = new Trend('notification_duration', true);
const searchDuration = new Trend('search_duration', true);
const questionDuration = new Trend('question_duration', true);
const errorRate = new Rate('error_rate');
const requestCount = new Counter('total_requests');

// ─── 설정 ─────────────────────────────────────────────────
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TEST_PASSWORD = 'testpass123a';
const NUM_USERS = parseInt(__ENV.NUM_USERS || '200');

// ─── 시나리오 ─────────────────────────────────────────────
export const options = {
    scenarios: {
        load_test: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 10 },   // warm-up
                { duration: '1m', target: 50 },     // load
                { duration: '30s', target: 100 },   // peak
                { duration: '1m', target: 100 },    // sustain peak
                { duration: '30s', target: 0 },     // cool-down
            ],
            gracefulRampDown: '10s',
        },
    },
    thresholds: {
        http_req_duration: ['p(95)<2000'],
        error_rate: ['rate<0.1'],
        feed_duration: ['p(95)<1500'],
        post_detail_duration: ['p(95)<1500'],
        like_duration: ['p(95)<1500'],
        notification_duration: ['p(95)<1500'],
        search_duration: ['p(95)<1000'],
    },
};

// ─── Setup: 시드 유저로 로그인 ────────────────────────────
export function setup() {
    console.log(`Logging in ${NUM_USERS} seeded users...`);

    const users = [];
    let failures = 0;

    for (let i = 1; i <= NUM_USERS; i++) {
        const username = `loaduser_${i}`;

        const loginRes = http.post(
            `${BASE_URL}/api/auth/login`,
            JSON.stringify({ username: username, password: TEST_PASSWORD }),
            { headers: { 'Content-Type': 'application/json' } }
        );

        if (loginRes.status === 200) {
            const loginBody = JSON.parse(loginRes.body);
            users.push({
                username: username,
                accessToken: loginBody.result.accessToken,
                memberId: loginBody.result.id,
            });
        } else {
            failures++;
            if (failures <= 3) {
                console.log(`Login failed for ${username}: ${loginRes.status}`);
            }
        }
    }

    console.log(`Successfully logged in ${users.length}/${NUM_USERS} users (${failures} failures)`);
    return { users };
}

// ─── 메인 테스트 함수 ─────────────────────────────────────
export default function (data) {
    const users = data.users;
    if (!users || users.length === 0) {
        console.log('No test users available');
        return;
    }

    const user = users[__VU % users.length];
    const authHeaders = {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${user.accessToken}`,
    };

    // 실제 사용 패턴 기반 가중치
    const scenario = weightedRandom([
        { weight: 30, fn: () => scenarioFeed(authHeaders) },
        { weight: 15, fn: () => scenarioPostDetail(authHeaders) },
        { weight: 10, fn: () => scenarioLike(authHeaders) },
        { weight: 15, fn: () => scenarioProfile(authHeaders, user) },
        { weight: 15, fn: () => scenarioNotifications(authHeaders) },
        { weight: 10, fn: () => scenarioQuestions(authHeaders) },
        { weight: 5, fn: () => scenarioSearch(authHeaders) },
    ]);

    scenario();
    sleep(Math.random() * 2 + 0.5);
}

// ─── 시나리오: 피드 조회 ──────────────────────────────────
function scenarioFeed(headers) {
    group('Feed', () => {
        // 피드 첫 페이지
        const feedRes = http.get(`${BASE_URL}/api/posts?feedType=ALL&size=10`, {
            headers,
            tags: { name: 'GET /api/posts' },
        });

        requestCount.add(1);
        feedDuration.add(feedRes.timings.duration);

        const feedOk = check(feedRes, {
            'feed status 200': (r) => r.status === 200,
        });
        errorRate.add(!feedOk);

        // 피드 두 번째 페이지 (cursor pagination)
        if (feedRes.status === 200) {
            try {
                const body = JSON.parse(feedRes.body);
                if (body.result && body.result.hasNext && body.result.nextCursor) {
                    const page2Res = http.get(
                        `${BASE_URL}/api/posts?feedType=ALL&size=10&cursor=${body.result.nextCursor}`,
                        { headers, tags: { name: 'GET /api/posts (page2)' } }
                    );
                    requestCount.add(1);
                    feedDuration.add(page2Res.timings.duration);
                    errorRate.add(page2Res.status !== 200);
                }
            } catch (e) { /* ignore */ }
        }

        // CORE 피드도 조회
        const coreFeedRes = http.get(`${BASE_URL}/api/posts?feedType=CORE&size=10`, {
            headers,
            tags: { name: 'GET /api/posts (CORE)' },
        });
        requestCount.add(1);
        feedDuration.add(coreFeedRes.timings.duration);
        errorRate.add(coreFeedRes.status !== 200);
    });
}

// ─── 시나리오: 게시글 상세 조회 ──────────────────────────
function scenarioPostDetail(headers) {
    group('PostDetail', () => {
        // 피드에서 게시글 ID 추출
        const feedRes = http.get(`${BASE_URL}/api/posts?feedType=ALL&size=10`, {
            headers,
            tags: { name: 'GET /api/posts' },
        });
        requestCount.add(1);

        if (feedRes.status === 200) {
            try {
                const body = JSON.parse(feedRes.body);
                const posts = body.result && body.result.posts;
                if (posts && posts.length > 0) {
                    // 랜덤 게시글 선택하여 상세 조회
                    const randomPost = posts[Math.floor(Math.random() * posts.length)];
                    const postId = randomPost.postId;

                    const detailRes = http.get(`${BASE_URL}/api/posts/${postId}`, {
                        headers,
                        tags: { name: 'GET /api/posts/{postId}' },
                    });
                    requestCount.add(1);
                    postDetailDuration.add(detailRes.timings.duration);

                    const detailOk = check(detailRes, {
                        'post detail status 200': (r) => r.status === 200,
                    });
                    errorRate.add(!detailOk);
                }
            } catch (e) { /* ignore */ }
        }
    });
}

// ─── 시나리오: 좋아요 ────────────────────────────────────
function scenarioLike(headers) {
    group('Like', () => {
        // 피드에서 게시글 ID 추출
        const feedRes = http.get(`${BASE_URL}/api/posts?feedType=ALL&size=10`, {
            headers,
            tags: { name: 'GET /api/posts' },
        });
        requestCount.add(1);

        if (feedRes.status === 200) {
            try {
                const body = JSON.parse(feedRes.body);
                const posts = body.result && body.result.posts;
                if (posts && posts.length > 0) {
                    const randomPost = posts[Math.floor(Math.random() * posts.length)];
                    const postId = randomPost.postId;

                    // 좋아요 누르기
                    const likeRes = http.post(`${BASE_URL}/api/posts/${postId}/likes`, null, {
                        headers,
                        tags: { name: 'POST /api/posts/{postId}/likes' },
                    });
                    requestCount.add(1);
                    likeDuration.add(likeRes.timings.duration);
                    // 이미 좋아요 상태(409)도 정상으로 취급
                    errorRate.add(likeRes.status !== 200 && likeRes.status !== 409);

                    // 좋아요 취소 (데이터 정합성 유지)
                    const unlikeRes = http.del(`${BASE_URL}/api/posts/${postId}/likes`, null, {
                        headers,
                        tags: { name: 'DELETE /api/posts/{postId}/likes' },
                    });
                    requestCount.add(1);
                    likeDuration.add(unlikeRes.timings.duration);
                    errorRate.add(unlikeRes.status !== 200 && unlikeRes.status !== 404);
                }
            } catch (e) { /* ignore */ }
        }
    });
}

// ─── 시나리오: 프로필 조회 ────────────────────────────────
function scenarioProfile(headers, user) {
    group('Profile', () => {
        // 내 프로필
        const profileRes = http.get(`${BASE_URL}/api/members/my-home`, {
            headers,
            tags: { name: 'GET /api/members/my-home' },
        });
        requestCount.add(1);
        profileDuration.add(profileRes.timings.duration);
        const profileOk = check(profileRes, {
            'profile status 200': (r) => r.status === 200,
        });
        errorRate.add(!profileOk);

        // 내 포스트 목록
        const postsRes = http.get(`${BASE_URL}/api/members/my-home/posts`, {
            headers,
            tags: { name: 'GET /api/members/my-home/posts' },
        });
        requestCount.add(1);
        errorRate.add(postsRes.status !== 200);

        // 다른 사용자 프로필 조회
        const otherUser = `loaduser_${Math.floor(Math.random() * 200) + 1}`;
        const otherRes = http.get(`${BASE_URL}/api/members/my-home/${otherUser}`, {
            headers,
            tags: { name: 'GET /api/members/my-home/{username}' },
        });
        requestCount.add(1);
        errorRate.add(otherRes.status !== 200);

        // 팔로워/팔로잉 목록
        const followersRes = http.get(`${BASE_URL}/api/followers`, {
            headers,
            tags: { name: 'GET /api/followers' },
        });
        requestCount.add(1);
        errorRate.add(followersRes.status !== 200);

        const followingsRes = http.get(`${BASE_URL}/api/followings`, {
            headers,
            tags: { name: 'GET /api/followings' },
        });
        requestCount.add(1);
        errorRate.add(followingsRes.status !== 200);
    });
}

// ─── 시나리오: 알림 조회 ──────────────────────────────────
function scenarioNotifications(headers) {
    group('Notifications', () => {
        // 읽지 않은 알림 확인
        const unreadRes = http.get(`${BASE_URL}/api/notifications/unread`, {
            headers,
            tags: { name: 'GET /api/notifications/unread' },
        });
        requestCount.add(1);
        notificationDuration.add(unreadRes.timings.duration);
        const unreadOk = check(unreadRes, {
            'unread notifications 200': (r) => r.status === 200,
        });
        errorRate.add(!unreadOk);

        // 알림 목록
        const listRes = http.get(`${BASE_URL}/api/notifications?size=10`, {
            headers,
            tags: { name: 'GET /api/notifications' },
        });
        requestCount.add(1);
        notificationDuration.add(listRes.timings.duration);
        errorRate.add(listRes.status !== 200);

        // 리마인더 설정 조회
        const reminderRes = http.get(`${BASE_URL}/api/notification-settings/reminder`, {
            headers,
            tags: { name: 'GET /api/notification-settings/reminder' },
        });
        requestCount.add(1);
        errorRate.add(reminderRes.status !== 200);
    });
}

// ─── 시나리오: 질문 ──────────────────────────────────────
function scenarioQuestions(headers) {
    group('Questions', () => {
        // 카테고리별 질문 조회 (categoryId 사용)
        const categoryId = Math.floor(Math.random() * 20) + 1;
        const basicRes = http.get(
            `${BASE_URL}/api/questions/basic?categoryId=${categoryId}`,
            { headers, tags: { name: 'GET /api/questions/basic' } }
        );
        requestCount.add(1);
        questionDuration.add(basicRes.timings.duration);
        const basicOk = check(basicRes, {
            'questions basic 200': (r) => r.status === 200,
        });
        errorRate.add(!basicOk);

        // 인기 질문 조회
        const popularRes = http.get(`${BASE_URL}/api/questions/popular`, {
            headers,
            tags: { name: 'GET /api/questions/popular' },
        });
        requestCount.add(1);
        questionDuration.add(popularRes.timings.duration);
        errorRate.add(popularRes.status !== 200);

        // 선택된 질문 조회
        const selectedRes = http.get(`${BASE_URL}/api/questions/selected`, {
            headers,
            tags: { name: 'GET /api/questions/selected' },
        });
        requestCount.add(1);
        questionDuration.add(selectedRes.timings.duration);
        errorRate.add(selectedRes.status !== 200);

        // 카테고리 목록 조회
        const categoriesRes = http.get(`${BASE_URL}/api/questions/categories`, {
            headers,
            tags: { name: 'GET /api/questions/categories' },
        });
        requestCount.add(1);
        errorRate.add(categoriesRes.status !== 200);

        // 질문 검색
        const searchTerms = ['일상', '감정', '관계', '목표', '감사'];
        const term = searchTerms[Math.floor(Math.random() * searchTerms.length)];
        const searchRes = http.get(
            `${BASE_URL}/api/questions/basic/search?keyword=${encodeURIComponent(term)}&categoryId=${categoryId}`,
            { headers, tags: { name: 'GET /api/questions/basic/search' } }
        );
        requestCount.add(1);
        errorRate.add(searchRes.status !== 200);
    });
}

// ─── 시나리오: 검색 ──────────────────────────────────────
function scenarioSearch(headers) {
    group('Search', () => {
        const keywords = ['nick', 'user', 'load', 'test'];
        const keyword = keywords[Math.floor(Math.random() * keywords.length)];

        const searchRes = http.get(
            `${BASE_URL}/api/search/members?keyword=${encodeURIComponent(keyword)}`,
            { headers, tags: { name: 'GET /api/search/members' } }
        );
        requestCount.add(1);
        searchDuration.add(searchRes.timings.duration);
        const searchOk = check(searchRes, {
            'search status 200': (r) => r.status === 200,
        });
        errorRate.add(!searchOk);

        // 검색 기록 조회
        const historyRes = http.get(`${BASE_URL}/api/search/members/history`, {
            headers,
            tags: { name: 'GET /api/search/members/history' },
        });
        requestCount.add(1);
        errorRate.add(historyRes.status !== 200);
    });
}

// ─── Teardown ─────────────────────────────────────────────
export function teardown(data) {
    console.log(`Load test completed. ${data.users.length} test users were used.`);
}

// ─── 유틸리티 ─────────────────────────────────────────────
function weightedRandom(items) {
    const totalWeight = items.reduce((sum, item) => sum + item.weight, 0);
    let random = Math.random() * totalWeight;

    for (const item of items) {
        random -= item.weight;
        if (random <= 0) {
            return item.fn;
        }
    }
    return items[items.length - 1].fn;
}
