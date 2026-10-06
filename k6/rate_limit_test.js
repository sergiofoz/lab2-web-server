import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

// Permite seleccionar un escenario por ejecución: `spike` o `stress`.
// Ejecutarlos por separado evita que el primer escenario consuma el bucket del segundo.
const selectedScenario = __ENV.SCENARIO || 'spike';

// El bucket permite una ráfaga de 50 peticiones y repone 10 tokens por segundo.
const BASE_URL = 'https://127.0.0.1:8443/time';
const REQUEST_TIMEOUT = '5s';

// Contadores explícitos para distinguir tráfico aceptado, limitado y errores inesperados.
// Las métricas Counter aparecen en el resumen de k6 y también se usan en los thresholds.
const responses200 = new Counter('responses_200');
const responses429 = new Counter('responses_429');
const unexpectedResponses = new Counter('unexpected_responses');

const spikeScenario = {
  executor: 'per-vu-iterations',
  exec: 'spike',
  vus: 100,
  iterations: 1,
  maxDuration: '2s',
};

const stressScenario = {
  executor: 'constant-arrival-rate',
  exec: 'stress',
  rate: 20,
  timeUnit: '1s',
  duration: '15s',
  // Hay VUs disponibles para mantener la tasa de llegada si alguna petición tarda más.
  preAllocatedVUs: 25,
  maxVUs: 50,
};

// Un certificado HTTPS local puede ser autofirmado. Esta opción es solo para el laboratorio;
// no debe copiarse a una configuración de pruebas contra servicios de producción.
export const options = {
  insecureSkipTLSVerify: true,
  scenarios:
    selectedScenario === 'spike'
      ? { spike: spikeScenario }
      : selectedScenario === 'stress'
        ? { stress: stressScenario }
        : {},
  thresholds: {
    // Confirma que el escenario ha observado tanto admisiones como limitaciones.
    responses_200: ['count>0'],
    responses_429: ['count>0'],
    // Ninguna respuesta distinta de 200 o 429 debe pasar inadvertida.
    unexpected_responses: ['count==0'],
    // El stress debe poder emitir las 20 iteraciones por segundo programadas.
    ...(selectedScenario === 'stress' ? { dropped_iterations: ['count==0'] } : {}),
  },
};

if (selectedScenario !== 'spike' && selectedScenario !== 'stress') {
  throw new Error(`SCENARIO must be "spike" or "stress"; received "${selectedScenario}"`);
}

function requestTime() {
  const response = http.get(BASE_URL, { timeout: REQUEST_TIMEOUT });

  if (response.status === 200) {
    responses200.add(1);
  } else if (response.status === 429) {
    responses429.add(1);
  } else {
    unexpectedResponses.add(1);
  }

  // El check principal acepta únicamente los dos resultados funcionales esperados.
  // Los contadores anteriores muestran cuántos 200 y cuántos 429 produjo cada ejecución.
  check(response, {
    'response status is expected (200 or 429)': (res) => res.status === 200 || res.status === 429,
  });
}

// Cada VU realiza una sola petición. Cien VUs generan una ráfaga superior al bucket inicial,
// por lo que la prueba debe observar peticiones aceptadas y otras rechazadas con HTTP 429.
export function spike() {
  requestTime();
}

// Este executor programa 20 nuevas iteraciones por segundo durante 15 segundos (300 peticiones).
// La tasa supera la recarga de 10 tokens/s, así que esperamos ver tanto HTTP 200 como HTTP 429.
export function stress() {
  requestTime();
}
