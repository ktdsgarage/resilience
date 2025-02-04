import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { getGatewayHost, createPointRequest } from './pointRequest.js';

const rateLimitCounter = new Counter('rate_limit_count');
const rateLimitRate = new Rate('rate_limit_percentage');
const cbCounter = new Counter('circuit_breaker_count');
const cbRate = new Rate('circuit_breaker_percentage');

/*
- 초당 사용자별 요청수=1초/thinkTime
- RPS(Request Per Seconds)=VU(Virtual User) * 초당 사용자별 요청수
아래 설정의 RPS
- 00초 ~ 20초: 100회 (10명 * 10회)
- 21초 ~ 40초: 200회 (20명 * 10회)
- 41초 ~ 60초: 300회 (30명 * 10회)
- 61초 ~ 80초: 400회 (40명 * 10회)
- 81초 ~ 100초: 500회 (50명 * 10회)
*/
export let options = {
    scenarios: {
        ramp_test: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '20s', target: 20 },
                { duration: '20s', target: 30 },
                { duration: '20s', target: 40 },
                { duration: '20s', target: 50 }
            ],
            gracefulRampDown: '30s'
        }
    }
};
const thinkTime = 0.1;  //초당 10회 호출하도록 think time 지정

export default function() {
    const payload = JSON.stringify(createPointRequest());
    const params = {
        headers: {
            'Accept': 'application/json',
            'Content-Type': 'application/json',
            'X-Partner-Type': "MART"
        }
    };

    let gatewayHost = getGatewayHost();
    let response = http.post(`http://${gatewayHost}/api/partner/mart/points/accumulate`, payload, params);
    let responseBody = response.body;

    // 다양한 상태 코드에 대한 상세 검증
    check(response, {
        'status is 200': (r) => r.status === 200,
        'status is 429': (r) => r.status === 429,   // Rate Limiting 체크 
        'status is 503': (r) => r.status === 503    // Circuit Breaker 체크 
    });
   
    // 에러 메트릭 업데이트
    rateLimitRate.add(response.status === 429);
    cbRate.add(response.status === 503);

    if (response.status === 429) {
        rateLimitCounter.add(1);
    }
    if (response.status === 503) {
        cbCounter.add(1);
    }

    sleep(thinkTime);
}
