import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';

// 스모크 테스트: 빠르게 API가 정상 동작하는지 확인
const errorRate = new Rate('error_rate');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TEST_PASSWORD = 'testpass123a';

export const options = {
    vus: 1,
    duration: '10s',
    thresholds: {
        http_req_duration: ['p(95)<3000'],
        error_rate: ['rate<0.05'],
    },
};

export function setup() {
    // 약관 조회
    const termsRes = http.get(`${BASE_URL}/api/terms`);
    let termsIds = [1, 2, 3];
    if (termsRes.status === 200) {
        try {
            const body = JSON.parse(termsRes.body);
            if (body.result && Array.isArray(body.result)) {
                termsIds = body.result.map(t => t.termsId);
            }
        } catch (e) { /* use default */ }
    }

    // 테스트 유저 1명 생성 (username 16자 제한)
    const ts = (Date.now() % 100000000).toString();
    const username = `k6s_${ts}`;
    const signupRes = http.post(
        `${BASE_URL}/api/auth/signup`,
        JSON.stringify({
            email: `${username}@loadtest.local`,
            name: '스모크테스트',
            username: username,
            password: TEST_PASSWORD,
            passwordCheck: TEST_PASSWORD,
            agreedTermsIds: termsIds,
        }),
        { headers: { 'Content-Type': 'application/json' } }
    );

    check(signupRes, { 'signup ok': (r) => r.status === 200 });

    const loginRes = http.post(
        `${BASE_URL}/api/auth/login`,
        JSON.stringify({ username, password: TEST_PASSWORD }),
        { headers: { 'Content-Type': 'application/json' } }
    );

    check(loginRes, { 'login ok': (r) => r.status === 200 });

    if (loginRes.status !== 200) {
        console.error(`Login failed: ${loginRes.body}`);
        return { token: null };
    }

    const body = JSON.parse(loginRes.body);
    return {
        token: body.result.accessToken,
        memberId: body.result.id,
    };
}

export default function (data) {
    if (!data.token) {
        console.error('No token available');
        return;
    }

    const headers = {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${data.token}`,
    };

    // 1. 피드 조회
    const feedRes = http.get(`${BASE_URL}/api/posts?feedType=ALL&size=5`, { headers });
    const feedOk = check(feedRes, { 'feed 200': (r) => r.status === 200 });
    errorRate.add(!feedOk);

    sleep(0.5);

    // 2. 프로필 조회
    const profileRes = http.get(`${BASE_URL}/api/members/my-home`, { headers });
    const profileOk = check(profileRes, { 'profile 200': (r) => r.status === 200 });
    errorRate.add(!profileOk);

    sleep(0.5);

    // 3. 알림 확인
    const notiRes = http.get(`${BASE_URL}/api/notifications/unread`, { headers });
    const notiOk = check(notiRes, { 'notifications 200': (r) => r.status === 200 });
    errorRate.add(!notiOk);

    sleep(0.5);

    // 4. 질문 조회
    const questRes = http.get(`${BASE_URL}/api/questions/selected`, { headers });
    const questOk = check(questRes, { 'questions 200': (r) => r.status === 200 });
    errorRate.add(!questOk);

    sleep(0.5);
}
