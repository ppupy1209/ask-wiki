// study ① Virtual Threads 부하 스크립트 (k6)
//
// 동시 사용자(VU)를 올려 I/O 바운드 엔드포인트(/api/bench/io)를 두드린다.
// 가상 스레드를 껐다(false) 켰다(true) 하며 두 번 돌려 처리량·p99를 비교한다.
//
// 실행 (k6 설치 불필요 — Docker로):
//   docker run --rm -i --network ask-wiki_default -e BASE=http://app:8080 grafana/k6 run - < bench/io-load.js
//
// 조절 가능한 환경변수: VUS(동시 사용자), DURATION, DELAY(엔드포인트 대기 ms), BASE(대상 주소)
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE || 'http://app:8080';
const ENDPOINT = __ENV.ENDPOINT || '/api/bench/downstream'; // 현실적 다운스트림 호출 (비교용 순수대기는 /api/bench/io)
const DELAY = __ENV.DELAY || '200';

export const options = {
  vus: Number(__ENV.VUS || 500),
  duration: __ENV.DURATION || '20s',
  summaryTrendStats: ['avg', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const res = http.get(`${BASE}${ENDPOINT}?delayMs=${DELAY}`);
  check(res, { 'status 200': (r) => r.status === 200 });
}
