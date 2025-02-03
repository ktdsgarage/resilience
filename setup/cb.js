// circuitbreaker.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { createPointRequest, getGatewayHost } from './pointRequest.js';

export let options = {
    scenarios: {
        circuit_breaker_test: {
            executor: 'constant-arrival-rate',
            rate: 200,             // 초당 요청수
            timeUnit: '1s',
            duration: '120s',
            preAllocatedVUs: 100,
            maxVUs: 1000,
        }
    }
};

export default function() {
    const payload = JSON.stringify(createPointRequest());
    const gatewayHost = getGatewayHost();

    const params = {
        headers: {
            'Accept': '*/*',
            'Content-Type': 'application/json',
            'X-Partner-Type': "MART"
        },
    };

    let response = http.post(
        `http://${gatewayHost}/api/points/accumulate`,
        payload,
        params
    );

    check(response, {
        'status is 200 or 503': (r) => [200, 503].includes(r.status),
        'circuit breaker open': (r) => r.status === 503 && r.headers['X-Circuit-Open']
    });

    sleep(0.1);
}
