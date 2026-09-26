// 2단계 실험: dispatch-engine 의 후보 검색만 일정한 속도로 부른다.
//   k6 run -e RATE=200 -e DURATION=30s candidates.js
// 가게 위치는 locations.js 가 라이더를 뿌리는 격자(위도 37.45~37.65, 경도 126.90~127.10) 안쪽에서 고른다.
import http from 'k6/http';
import { check } from 'k6';

const RATE = Number(__ENV.RATE || 200);
const URL = __ENV.URL || 'http://localhost:8093';

export const options = {
  discardResponseBodies: false,
  scenarios: {
    probe: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: __ENV.DURATION || '30s',
      preAllocatedVUs: Math.min(400, RATE),
      maxVUs: 400,
    },
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const lat = 37.47 + Math.random() * 0.16;
  const lng = 126.92 + Math.random() * 0.16;
  const res = http.get(`${URL}/api/candidates?lat=${lat}&lng=${lng}`, { timeout: '10s' });
  check(res, { ok: (r) => r.status === 200, found: (r) => r.status === 200 && r.json('data.found') > 0 });
}
