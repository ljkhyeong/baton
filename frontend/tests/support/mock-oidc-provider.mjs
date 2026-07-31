import {
  createHash,
  generateKeyPairSync,
  randomBytes,
  sign,
  timingSafeEqual,
} from 'node:crypto';
import http from 'node:http';

const DEFAULT_BIND_HOST = '127.0.0.1';
const ALLOWED_BIND_HOSTS = new Set(['127.0.0.1', '0.0.0.0']);
const DEFAULT_ISSUER = 'https://fullstack-oidc.baton.invalid';
const DEFAULT_CLIENT_ID = 'baton-fullstack-e2e';
const DEFAULT_CLIENT_SECRET = 'baton-fullstack-e2e-client-secret';
const DEFAULT_REDIRECT_URI =
  'http://127.0.0.1:8080/api/v1/auth/oidc/callback/google';
const AUTHORIZATION_LIFETIME_MS = 2 * 60 * 1000;
const CODE_LIFETIME_MS = 2 * 60 * 1000;
const TOKEN_LIFETIME_SECONDS = 5 * 60;
const MAXIMUM_FORM_BYTES = 8 * 1024;
const PKCE_VERIFIER_PATTERN = /^[A-Za-z0-9._~-]{43,128}$/;
const PKCE_CHALLENGE_PATTERN = /^[A-Za-z0-9_-]{43}$/;
const SUBJECT_PATTERN = /^[A-Za-z0-9._:@-]{1,255}$/;

function requiredText(name, fallback) {
  const value = process.env[name] ?? fallback;
  if (typeof value !== 'string' || value.length === 0) {
    throw new Error(`${name} 값이 필요합니다`);
  }
  if (/\r|\n|\0/.test(value)) {
    throw new Error(`${name} 값에 제어 문자를 사용할 수 없습니다`);
  }
  return value;
}

function parsePort() {
  const source = process.env.BATON_MOCK_OIDC_PORT ?? '0';
  if (!/^\d{1,5}$/.test(source)) {
    throw new Error('BATON_MOCK_OIDC_PORT는 0~65535 정수여야 합니다');
  }
  const port = Number(source);
  if (!Number.isSafeInteger(port) || port < 0 || port > 65535) {
    throw new Error('BATON_MOCK_OIDC_PORT는 0~65535 정수여야 합니다');
  }
  return port;
}

function parseBindHost() {
  const host = process.env.BATON_MOCK_OIDC_BIND_HOST ?? DEFAULT_BIND_HOST;
  if (!ALLOWED_BIND_HOSTS.has(host)) {
    throw new Error(
      'BATON_MOCK_OIDC_BIND_HOST는 127.0.0.1 또는 격리 컨테이너용 0.0.0.0이어야 합니다',
    );
  }
  return host;
}

function validateIssuer(value) {
  let issuer;
  try {
    issuer = new URL(value);
  } catch {
    throw new Error('BATON_MOCK_OIDC_ISSUER는 올바른 HTTPS URL이어야 합니다');
  }
  if (
    issuer.protocol !== 'https:'
    || !issuer.hostname
    || issuer.username
    || issuer.password
    || issuer.search
    || issuer.hash
  ) {
    throw new Error('BATON_MOCK_OIDC_ISSUER는 올바른 HTTPS URL이어야 합니다');
  }
  return issuer.toString().replace(/\/$/, '');
}

function validateRedirectUri(value) {
  let redirectUri;
  try {
    redirectUri = new URL(value);
  } catch {
    throw new Error('BATON_MOCK_OIDC_REDIRECT_URI가 올바르지 않습니다');
  }
  const httpLoopback =
    redirectUri.protocol === 'http:'
    && redirectUri.hostname === 'localhost'
    && Boolean(redirectUri.port);
  const httpsLocalhost =
    redirectUri.protocol === 'https:'
    && redirectUri.hostname === 'baton.localhost'
    && !redirectUri.port;
  if (
    (!httpLoopback && !httpsLocalhost)
    || redirectUri.username
    || redirectUri.password
    || redirectUri.pathname !== '/api/v1/auth/oidc/callback/google'
    || redirectUri.search
    || redirectUri.hash
  ) {
    throw new Error(
      'BATON_MOCK_OIDC_REDIRECT_URI는 정확한 HTTP loopback 또는 HTTPS baton.localhost callback이어야 합니다',
    );
  }
  return redirectUri.toString();
}

function base64Url(value) {
  return Buffer.from(value).toString('base64url');
}

function randomIdentifier() {
  return randomBytes(32).toString('base64url');
}

function exactQueryValue(searchParams, name) {
  const values = searchParams.getAll(name);
  return values.length === 1 ? values[0] : null;
}

function constantTimeEqual(left, right) {
  const leftBuffer = Buffer.from(left);
  const rightBuffer = Buffer.from(right);
  return leftBuffer.length === rightBuffer.length
    && timingSafeEqual(leftBuffer, rightBuffer);
}

function formValue(form, name) {
  const values = form.getAll(name);
  return values.length === 1 ? values[0] : null;
}

function responseHeaders(contentType) {
  return {
    'Cache-Control': 'no-store',
    Pragma: 'no-cache',
    'Referrer-Policy': 'no-referrer',
    'X-Content-Type-Options': 'nosniff',
    'Content-Type': contentType,
  };
}

function sendJson(response, status, body) {
  response.writeHead(status, responseHeaders('application/json; charset=utf-8'));
  response.end(`${JSON.stringify(body)}\n`);
}

function sendOAuthError(response, status, error) {
  sendJson(response, status, { error });
}

function sendMethodNotAllowed(response, allowedMethod) {
  response.writeHead(405, {
    ...responseHeaders('application/json; charset=utf-8'),
    Allow: allowedMethod,
  });
  response.end(`${JSON.stringify({ error: 'method_not_allowed' })}\n`);
}

function readForm(request) {
  return new Promise((resolve, reject) => {
    const contentType = request.headers['content-type'] ?? '';
    if (!contentType.toLowerCase().startsWith('application/x-www-form-urlencoded')) {
      reject(new Error('unsupported_content_type'));
      return;
    }

    let receivedBytes = 0;
    const chunks = [];
    request.on('data', (chunk) => {
      receivedBytes += chunk.length;
      if (receivedBytes > MAXIMUM_FORM_BYTES) {
        reject(new Error('request_too_large'));
        request.destroy();
        return;
      }
      chunks.push(chunk);
    });
    request.on('end', () => {
      try {
        resolve(new URLSearchParams(Buffer.concat(chunks).toString('utf8')));
      } catch {
        reject(new Error('invalid_form'));
      }
    });
    request.on('error', () => reject(new Error('request_failed')));
  });
}

function escapeHtml(value) {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

function decodeBasicClientAuthorization(header) {
  if (typeof header !== 'string' || !header.startsWith('Basic ')) {
    return null;
  }
  try {
    const decoded = Buffer.from(header.slice('Basic '.length), 'base64')
      .toString('utf8');
    const separator = decoded.indexOf(':');
    if (separator < 0) {
      return null;
    }
    return {
      clientId: decodeURIComponent(decoded.slice(0, separator)),
      clientSecret: decodeURIComponent(decoded.slice(separator + 1)),
    };
  } catch {
    return null;
  }
}

function loadConfiguration() {
  const configuredBindHost = parseBindHost();
  const configuredIssuer = validateIssuer(
    requiredText('BATON_MOCK_OIDC_ISSUER', DEFAULT_ISSUER),
  );
  const configuredClientId = requiredText(
    'BATON_MOCK_OIDC_CLIENT_ID',
    DEFAULT_CLIENT_ID,
  );
  const configuredClientSecret = requiredText(
    'BATON_MOCK_OIDC_CLIENT_SECRET',
    DEFAULT_CLIENT_SECRET,
  );
  const configuredRedirectUri = validateRedirectUri(
    requiredText('BATON_MOCK_OIDC_REDIRECT_URI', DEFAULT_REDIRECT_URI),
  );
  const selectedSubject = process.env.BATON_MOCK_OIDC_SUBJECT;
  if (selectedSubject !== undefined && !SUBJECT_PATTERN.test(selectedSubject)) {
    throw new Error(
      'BATON_MOCK_OIDC_SUBJECT는 허용된 문자로 구성된 1~255자여야 합니다',
    );
  }
  return {
    bindHost: configuredBindHost,
    issuer: configuredIssuer,
    clientId: configuredClientId,
    clientSecret: configuredClientSecret,
    redirectUri: configuredRedirectUri,
    configuredSubject: selectedSubject,
  };
}

let configuration;
try {
  configuration = loadConfiguration();
} catch (error) {
  process.stderr.write(
    `[mock-oidc] ${error instanceof Error ? error.message : '설정이 올바르지 않습니다'}\n`,
  );
  process.exit(1);
}
const {
  bindHost,
  issuer,
  clientId,
  clientSecret,
  redirectUri,
  configuredSubject,
} = configuration;

const keyId = `mock-oidc-${randomBytes(8).toString('hex')}`;
const { privateKey, publicKey } = generateKeyPairSync('rsa', {
  modulusLength: 2048,
  publicExponent: 0x10001,
});
const exportedPublicKey = publicKey.export({ format: 'jwk' });
const publicJwk = {
  kty: 'RSA',
  kid: keyId,
  use: 'sig',
  alg: 'RS256',
  n: exportedPublicKey.n,
  e: exportedPublicKey.e,
};

const pendingAuthorizations = new Map();
const authorizationCodes = new Map();
const accessTokens = new Map();

function cleanExpiredEntries(now = Date.now()) {
  for (const [transactionId, authorization] of pendingAuthorizations) {
    if (authorization.expiresAt <= now) {
      pendingAuthorizations.delete(transactionId);
    }
  }
  for (const [code, authorization] of authorizationCodes) {
    if (authorization.expiresAt <= now) {
      authorizationCodes.delete(code);
    }
  }
  for (const [token, authorization] of accessTokens) {
    if (authorization.expiresAt <= now) {
      accessTokens.delete(token);
    }
  }
}

function issueAuthorizationCode(authorization, subject, response) {
  const code = randomIdentifier();
  authorizationCodes.set(code, {
    ...authorization,
    subject,
    authorizedAt: Math.floor(Date.now() / 1000),
    expiresAt: Date.now() + CODE_LIFETIME_MS,
  });

  const callback = new URL(authorization.redirectUri);
  callback.searchParams.set('code', code);
  callback.searchParams.set('state', authorization.state);
  response.writeHead(302, {
    ...responseHeaders('text/plain; charset=utf-8'),
    Location: callback.toString(),
  });
  response.end('Redirecting\n');
}

function validSubject(value) {
  return typeof value === 'string' && SUBJECT_PATTERN.test(value);
}

async function handleAuthorization(request, response, requestUrl) {
  if (request.method === 'GET') {
    const responseType = exactQueryValue(requestUrl.searchParams, 'response_type');
    const requestedClientId = exactQueryValue(requestUrl.searchParams, 'client_id');
    const requestedRedirectUri = exactQueryValue(
      requestUrl.searchParams,
      'redirect_uri',
    );
    const scope = exactQueryValue(requestUrl.searchParams, 'scope');
    const state = exactQueryValue(requestUrl.searchParams, 'state');
    const nonce = exactQueryValue(requestUrl.searchParams, 'nonce');
    const codeChallenge = exactQueryValue(
      requestUrl.searchParams,
      'code_challenge',
    );
    const codeChallengeMethod = exactQueryValue(
      requestUrl.searchParams,
      'code_challenge_method',
    );

    if (
      responseType !== 'code'
      || requestedClientId !== clientId
      || requestedRedirectUri !== redirectUri
      || !scope?.split(/\s+/).includes('openid')
      || !state
      || state.length > 1024
      || !nonce
      || nonce.length > 1024
      || !PKCE_CHALLENGE_PATTERN.test(codeChallenge ?? '')
      || codeChallengeMethod !== 'S256'
    ) {
      sendOAuthError(response, 400, 'invalid_request');
      return;
    }

    const authorization = {
      clientId,
      redirectUri,
      scope,
      state,
      nonce,
      codeChallenge,
    };
    const loginHint = exactQueryValue(requestUrl.searchParams, 'login_hint');
    const automaticSubject = configuredSubject ?? loginHint;
    if (automaticSubject !== null && automaticSubject !== undefined) {
      if (!validSubject(automaticSubject)) {
        sendOAuthError(response, 400, 'invalid_request');
        return;
      }
      issueAuthorizationCode(authorization, automaticSubject, response);
      return;
    }

    const transactionId = randomIdentifier();
    pendingAuthorizations.set(transactionId, {
      ...authorization,
      expiresAt: Date.now() + AUTHORIZATION_LIFETIME_MS,
    });
    const safeTransactionId = escapeHtml(transactionId);
    const html = `<!doctype html>
<html lang="ko">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>BATON OIDC 테스트 공급자</title>
  </head>
  <body>
    <main>
      <h1>테스트 계정 선택</h1>
      <p>이 화면은 loopback E2E 전용 공급자입니다.</p>
      <form method="post" action="/authorize">
        <input type="hidden" name="transaction" value="${safeTransactionId}">
        <button type="submit" name="subject" value="owner">owner 계정으로 계속</button>
        <button type="submit" name="subject" value="member">member 계정으로 계속</button>
      </form>
    </main>
  </body>
</html>\n`;
    response.writeHead(200, responseHeaders('text/html; charset=utf-8'));
    response.end(html);
    return;
  }

  if (request.method !== 'POST') {
    sendMethodNotAllowed(response, 'GET, POST');
    return;
  }

  let form;
  try {
    form = await readForm(request);
  } catch {
    sendOAuthError(response, 400, 'invalid_request');
    return;
  }
  const transactionId = formValue(form, 'transaction');
  const subject = formValue(form, 'subject');
  const authorization = transactionId
    ? pendingAuthorizations.get(transactionId)
    : undefined;
  if (transactionId) {
    pendingAuthorizations.delete(transactionId);
  }
  if (
    !authorization
    || authorization.expiresAt <= Date.now()
    || !validSubject(subject)
  ) {
    sendOAuthError(response, 400, 'invalid_request');
    return;
  }
  issueAuthorizationCode(authorization, subject, response);
}

function authenticateClient(request, form) {
  const basicClient = decodeBasicClientAuthorization(
    request.headers.authorization,
  );
  const formClientId = formValue(form, 'client_id');
  const formClientSecret = formValue(form, 'client_secret');

  if (basicClient) {
    if (formClientSecret !== null) {
      return false;
    }
    return constantTimeEqual(basicClient.clientId, clientId)
      && constantTimeEqual(basicClient.clientSecret, clientSecret)
      && (formClientId === null || constantTimeEqual(formClientId, clientId));
  }

  return formClientId !== null
    && formClientSecret !== null
    && constantTimeEqual(formClientId, clientId)
    && constantTimeEqual(formClientSecret, clientSecret);
}

function signedIdToken(authorization, nowInSeconds) {
  const header = base64Url(JSON.stringify({
    alg: 'RS256',
    typ: 'JWT',
    kid: keyId,
  }));
  const payload = base64Url(JSON.stringify({
    iss: issuer,
    sub: authorization.subject,
    aud: authorization.clientId,
    exp: nowInSeconds + TOKEN_LIFETIME_SECONDS,
    iat: nowInSeconds,
    auth_time: authorization.authorizedAt,
    nonce: authorization.nonce,
  }));
  const signingInput = `${header}.${payload}`;
  const signature = sign('RSA-SHA256', Buffer.from(signingInput), privateKey)
    .toString('base64url');
  return `${signingInput}.${signature}`;
}

async function handleToken(request, response) {
  if (request.method !== 'POST') {
    sendMethodNotAllowed(response, 'POST');
    return;
  }

  let form;
  try {
    form = await readForm(request);
  } catch {
    sendOAuthError(response, 400, 'invalid_request');
    return;
  }
  if (!authenticateClient(request, form)) {
    response.writeHead(401, {
      ...responseHeaders('application/json; charset=utf-8'),
      'WWW-Authenticate': 'Basic realm="mock-oidc"',
    });
    response.end(`${JSON.stringify({ error: 'invalid_client' })}\n`);
    return;
  }

  const code = formValue(form, 'code');
  const authorization = code ? authorizationCodes.get(code) : undefined;
  if (code) {
    authorizationCodes.delete(code);
  }
  const verifier = formValue(form, 'code_verifier');
  const expectedChallenge = verifier && PKCE_VERIFIER_PATTERN.test(verifier)
    ? createHash('sha256').update(verifier).digest('base64url')
    : null;
  if (
    formValue(form, 'grant_type') !== 'authorization_code'
    || !authorization
    || authorization.expiresAt <= Date.now()
    || formValue(form, 'redirect_uri') !== authorization.redirectUri
    || expectedChallenge === null
    || !constantTimeEqual(expectedChallenge, authorization.codeChallenge)
  ) {
    sendOAuthError(response, 400, 'invalid_grant');
    return;
  }

  const nowInSeconds = Math.floor(Date.now() / 1000);
  const accessToken = randomIdentifier();
  accessTokens.set(accessToken, {
    subject: authorization.subject,
    expiresAt: Date.now() + TOKEN_LIFETIME_SECONDS * 1000,
  });
  sendJson(response, 200, {
    access_token: accessToken,
    token_type: 'Bearer',
    expires_in: TOKEN_LIFETIME_SECONDS,
    scope: authorization.scope,
    id_token: signedIdToken(authorization, nowInSeconds),
  });
}

function handleUserInfo(request, response) {
  if (request.method !== 'GET') {
    sendMethodNotAllowed(response, 'GET');
    return;
  }
  const authorization = request.headers.authorization;
  const token = typeof authorization === 'string'
    && authorization.startsWith('Bearer ')
    ? authorization.slice('Bearer '.length)
    : null;
  const access = token ? accessTokens.get(token) : undefined;
  if (!access || access.expiresAt <= Date.now()) {
    response.writeHead(401, {
      ...responseHeaders('application/json; charset=utf-8'),
      'WWW-Authenticate': 'Bearer',
    });
    response.end(`${JSON.stringify({ error: 'invalid_token' })}\n`);
    return;
  }
  sendJson(response, 200, { sub: access.subject });
}

function createServer() {
  const server = http.createServer(async (request, response) => {
    cleanExpiredEntries();
    const address = server.address();
    const boundPort = address && typeof address !== 'string'
      ? address.port
      : 0;
    const baseUrl = `http://${bindHost}:${boundPort}`;
    const endpoints = {
      authorizationUri: `${baseUrl}/authorize`,
      tokenUri: `${baseUrl}/token`,
      jwkSetUri: `${baseUrl}/jwks`,
      userInfoUri: `${baseUrl}/userinfo`,
    };
    let requestUrl;
    try {
      requestUrl = new URL(request.url ?? '/', baseUrl);
    } catch {
      sendOAuthError(response, 400, 'invalid_request');
      return;
    }

    try {
      if (requestUrl.pathname === '/authorize') {
        await handleAuthorization(request, response, requestUrl);
        return;
      }
      if (requestUrl.pathname === '/token') {
        await handleToken(request, response);
        return;
      }
      if (requestUrl.pathname === '/jwks') {
        if (request.method !== 'GET') {
          sendMethodNotAllowed(response, 'GET');
          return;
        }
        sendJson(response, 200, { keys: [publicJwk] });
        return;
      }
      if (requestUrl.pathname === '/userinfo') {
        handleUserInfo(request, response);
        return;
      }
      if (requestUrl.pathname === '/.well-known/openid-configuration') {
        if (request.method !== 'GET') {
          sendMethodNotAllowed(response, 'GET');
          return;
        }
        sendJson(response, 200, {
          issuer,
          authorization_endpoint: endpoints.authorizationUri,
          token_endpoint: endpoints.tokenUri,
          jwks_uri: endpoints.jwkSetUri,
          userinfo_endpoint: endpoints.userInfoUri,
          response_types_supported: ['code'],
          subject_types_supported: ['public'],
          id_token_signing_alg_values_supported: ['RS256'],
          code_challenge_methods_supported: ['S256'],
          token_endpoint_auth_methods_supported: [
            'client_secret_basic',
            'client_secret_post',
          ],
          scopes_supported: ['openid', 'profile', 'email'],
        });
        return;
      }
      if (requestUrl.pathname === '/health') {
        if (request.method !== 'GET') {
          sendMethodNotAllowed(response, 'GET');
          return;
        }
        sendJson(response, 200, { status: 'UP' });
        return;
      }
      sendJson(response, 404, { error: 'not_found' });
    } catch {
      if (!response.headersSent) {
        sendOAuthError(response, 500, 'server_error');
      } else {
        response.end();
      }
    }
  });

  server.on('clientError', (_error, socket) => {
    if (socket.writable) {
      socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n');
    }
  });
  server.on('error', () => {
    process.stderr.write('[mock-oidc] loopback 서버 시작에 실패했습니다\n');
    process.exitCode = 1;
  });
  return server;
}

function run() {
  const requestedPort = parsePort();
  const server = createServer();
  server.listen(requestedPort, bindHost, () => {
    const address = server.address();
    if (!address || typeof address === 'string') {
      process.stderr.write('[mock-oidc] loopback 주소를 확인하지 못했습니다\n');
      process.exitCode = 1;
      server.close();
      return;
    }

    const baseUrl = `http://${bindHost}:${address.port}`;
    const ready = {
      status: 'ready',
      issuer,
      authorizationUri: `${baseUrl}/authorize`,
      tokenUri: `${baseUrl}/token`,
      jwkSetUri: `${baseUrl}/jwks`,
      userInfoUri: `${baseUrl}/userinfo`,
      healthUri: `${baseUrl}/health`,
      clientId,
    };
    process.stdout.write(`${JSON.stringify(ready)}\n`);
  });

  const shutdown = () => {
    server.close(() => process.exit(0));
  };
  process.once('SIGINT', shutdown);
  process.once('SIGTERM', shutdown);
}

try {
  run();
} catch (error) {
  process.stderr.write(
    `[mock-oidc] ${error instanceof Error ? error.message : '시작에 실패했습니다'}\n`,
  );
  process.exitCode = 1;
}
