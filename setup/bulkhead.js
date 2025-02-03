import http from 'k6/http';
import { check, sleep } from 'k6';
import { getGatewayHost, createPointRequest } from './pointRequest.js';

export let options = {
    scenarios: {
        bulkhead_test: {
            executor: 'constant-vus',
            vus: 200,
            duration: '60s',
            tags: { test_type: 'bulkhead' },
        }
    }
};

export default function() {
    const payload = JSON.stringify(createPointRequest());

    const params = {
        headers: {
            'Accept': '*/*',
            'Content-Type': 'application/json',
            'X-Partner-Type': "MART"
        },
    };
    let gatewayHost = getGatewayHost();
    let response = http.post(`http://${gatewayHost}/api/points/accumulate`, payload, params);

    check(response, {
        'status is 200 or 503': (r) => [200, 503].includes(r.status)
    });

    sleep(1);
}

