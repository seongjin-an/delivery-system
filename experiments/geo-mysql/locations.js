// 2단계 실험(위치 스트림 실험에서 가져옴): location-ingest 에 좌표를 일정한 속도로 넣는다.
//   k6 run -e RATE=3000 -e DURATION=60s load.js
//
// 라이더 한 명은 VU 하나에만 속한다. VU 는 요청을 하나씩 순서대로 보내니까 같은 라이더의
// 좌표 두 개가 동시에 날아가는 일이 없다. 그래야 geo-indexer 에서 순서가 뒤집힌 게 보이면
// 그게 브로커 쪽에서 생긴 거라고 말할 수 있다.
import http from 'k6/http';

const RATE = Number(__ENV.RATE || 3000);
const DURATION = __ENV.DURATION || '60s';
const VUS = Number(__ENV.VUS || 300);
const RIDERS = Number(__ENV.RIDERS || 3000);
const PER_VU = Math.max(1, Math.floor(RIDERS / VUS));
const URL = __ENV.URL || 'http://localhost:8091';
// 시뮬레이터 라이더(TSID)나 손으로 넣은 테스트 라이더와 안 겹치게 따로 뗀 번호대
const BASE_ID = 810000000000000;

export const options = {
  discardResponseBodies: true,
  scenarios: {
    ingest: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: VUS,
      maxVUs: VUS,
    },
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

const params = { headers: { 'Content-Type': 'application/json' }, timeout: '10s' };

export default function () {
  const slot = __ITER % PER_VU;
  const moved = Math.floor(__ITER / PER_VU);
  const idx = (__VU - 1) * PER_VU + slot;
  // 서울 남쪽에 100 x 30 격자로 뿌리고, 한 번에 북쪽으로 20m 씩 걷게 한다 (15m 필터를 넘기려고).
  // 500 걸음(10km)마다 제자리로 돌아간다.
  const lat = 37.45 + (idx % 100) * 0.002 + (moved % 500) * 0.00018;
  const lng = 126.90 + Math.floor(idx / 100) * 0.002;
  const body = JSON.stringify({ lat, lng, sentAt: new Date().toISOString() });
  http.post(`${URL}/api/riders/${BASE_ID + idx}/location`, body, params);
}
