// Swagger UI do ambiente local.
// Ao abrir a página, obtém um token de teste em POST /dev/token e já deixa a UI autorizada,
// para que as duas APIs possam ser chamadas direto com "Try it out" > "Execute".
// O token fica salvo no navegador (persistAuthorization), então o comerciante de teste é o mesmo
// entre recarregamentos; um token novo só é emitido quando o anterior expira.
// Em produção não existe /dev/token: a chamada falha em silêncio e o Authorize segue manual.

const SCHEME = 'bearer';

function tokenStillValid(token) {
  try {
    const payload = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
    return payload.exp * 1000 > Date.now() + 60_000;
  } catch (e) {
    return false;
  }
}

async function autoAuthorize(ui) {
  const current = ui.authSelectors.authorized().getIn([SCHEME, 'value']);
  if (current && tokenStillValid(current)) {
    return;
  }
  try {
    const response = await fetch('/dev/token', { method: 'POST' });
    if (!response.ok) {
      return;
    }
    const token = await response.json();
    ui.preauthorizeApiKey(SCHEME, token.access_token);
    console.info('Swagger UI autorizada para o comerciante de teste', token.merchant_id);
  } catch (e) {
    console.warn('Token automático indisponível; use Authorize manualmente.', e);
  }
}

window.onload = function () {
  window.ui = SwaggerUIBundle({
    urls: [
      { url: '/openapi/launch.json', name: 'Lançamentos' },
      { url: '/openapi/consolidation.json', name: 'Consolidado diário' },
    ],
    'urls.primaryName': 'Lançamentos',
    dom_id: '#swagger-ui',
    deepLinking: true,
    presets: [SwaggerUIBundle.presets.apis, SwaggerUIStandalonePreset],
    plugins: [SwaggerUIBundle.plugins.DownloadUrl],
    layout: 'StandaloneLayout',
    persistAuthorization: true,
    displayRequestDuration: true,
    tryItOutEnabled: true,
    onComplete: () => autoAuthorize(window.ui),
  });
};
