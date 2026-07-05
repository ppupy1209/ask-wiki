// study ① Virtual Threads: 실제 오케스트레이션 엔드포인트(/api/bench/orchestrate) 부하 스크립트
//
// 한 요청이 여러 다운스트림 호출 + DB 조회를 순차로 수행하는 BFF/게이트웨이 패턴을 부하 건다.
// 가상 스레드를 껐다(false) 켰다(true) 하며 두 번 돌려 처리량·p99를 비교한다.
//
// 실행 (k6 설치 불필요, Docker):
//   docker run --rm -i --network ask-wiki_default -e BASE=http://app:8080 grafana/k6 run - < bench/orchestrate-load.js
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE || 'http://app:8080';
const MS = __ENV.MS || '120'; // 다운스트림 서비스 1회 지연(ms)

export const options = {
  vus: Number(__ENV.VUS || 500),
  duration: __ENV.DURATION || '20s',
  summaryTrendStats: ['avg', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const res = http.get(`${BASE}/api/bench/orchestrate?ms=${MS}`);
  check(res, { 'status 200': (r) => r.status === 200 });
}
