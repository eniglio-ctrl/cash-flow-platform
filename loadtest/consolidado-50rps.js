// Teste de carga do RNF: "em picos, o consolidado recebe 50 req/s com no máximo 5% de perda".
// Cenários: 50 req/s sustentados (requisito) e 150 req/s (3x, margem). O teste FALHA se
// a taxa de erro passar de 5% (limite de negócio) ou 1% (nosso SLO), ou se o p95 passar de 200 ms.
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const MERCHANTS = 20;

export const options = {
  scenarios: {
    pico_50rps: {
      executor: 'constant-arrival-rate', rate: 50, timeUnit: '1s', duration: '60s',
      preAllocatedVUs: 50, maxVUs: 200,
    },
    margem_150rps: {
      executor: 'constant-arrival-rate', rate: 150, timeUnit: '1s', duration: '60s', startTime: '65s',
      preAllocatedVUs: 100, maxVUs: 400,
    },
  },
  thresholds: {
    'http_req_failed{scenario:pico_50rps}': ['rate<0.05', 'rate<0.01'],
    'http_req_failed{scenario:margem_150rps}': ['rate<0.05'],
    'http_req_duration{scenario:pico_50rps}': ['p(95)<200'],
  },
};

export function setup() {
  const tokens = [];
  for (let i = 0; i < MERCHANTS; i++) {
    const t = http.post(`${BASE}/dev/token`).json();
    const headers = { Authorization: `Bearer ${t.access_token}`, 'Content-Type': 'application/json' };
    // massa de dados: alguns lançamentos nos últimos 30 dias para cada comerciante
    for (let d = 0; d < 30; d += 3) {
      const date = new Date(Date.now() - d * 86400000 - 3 * 3600000).toISOString().slice(0, 10);
      http.post(`${BASE}/api/v1/lancamentos`, JSON.stringify({
        date, type: d % 2 ? 'DEBIT' : 'CREDIT', amount: (10 + d).toFixed(2), description: 'carga',
      }), { headers: { ...headers, 'Idempotency-Key': `seed-${i}-${d}-${t.merchant_id}` } });
    }
    tokens.push(t.access_token);
  }
  return { tokens };
}

export default function (data) {
  const token = data.tokens[Math.floor(Math.random() * data.tokens.length)];
  const daysAgo = Math.floor(Math.random() * 30);
  const date = new Date(Date.now() - daysAgo * 86400000 - 3 * 3600000).toISOString().slice(0, 10);
  const res = http.get(`${BASE}/api/v1/consolidados/${date}`, {
    headers: { Authorization: `Bearer ${token}` },
    tags: { name: 'GET /consolidados/{date}' },
  });
  check(res, { 'status 200': (r) => r.status === 200 });
}
