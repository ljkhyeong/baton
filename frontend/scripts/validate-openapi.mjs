import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { load } from 'js-yaml'

const CONTRACT = [
  {
    id: 'getSystemStatus',
    method: 'get',
    path: '/api/v1/system/status',
    statuses: ['200'],
    summary: '시스템 상태 조회',
  },
  {
    body: true,
    id: 'createWorkspace',
    method: 'post',
    path: '/api/v1/workspaces',
    requestHeaders: ['Idempotency-Key'],
    responseHeaders: ['Cache-Control', 'Location'],
    statuses: ['201', '400', '403', '409'],
    summary: '워크스페이스 생성',
  },
  {
    id: 'getWorkspace',
    method: 'get',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/workspace',
    requestHeaders: ['X-Baton-Access-Key'],
    responseHeaders: ['Cache-Control'],
    statuses: ['200', '403'],
    summary: '워크스페이스 조회',
  },
  {
    id: 'rotateAccessKey',
    method: 'post',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/access-key/rotate',
    requestHeaders: ['Idempotency-Key', 'X-Baton-Access-Key'],
    responseHeaders: ['Cache-Control'],
    statuses: ['200', '409'],
    summary: '접근 키 회전',
  },
  {
    id: 'recoverAccessKey',
    method: 'post',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/access-key/recover',
    requestHeaders: ['Idempotency-Key', 'X-Baton-Recovery-Key'],
    responseHeaders: ['Cache-Control'],
    statuses: ['200', '403'],
    summary: '접근 키 복구',
  },
  {
    body: true,
    id: 'createRole',
    method: 'post',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/roles',
    requestHeaders: ['Idempotency-Key', 'X-Baton-Access-Key'],
    statuses: ['201', '409'],
    summary: '역할 생성',
  },
  {
    body: true,
    id: 'updateRole',
    method: 'put',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/roles/{roleId}',
    requestHeaders: ['X-Baton-Access-Key'],
    statuses: ['200', '404', '409'],
    summary: '역할 수정',
  },
  {
    body: true,
    id: 'createRoutine',
    method: 'post',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/routines',
    requestHeaders: ['Idempotency-Key', 'X-Baton-Access-Key'],
    statuses: ['201', '400', '409'],
    summary: '루틴 생성',
  },
  {
    body: true,
    id: 'updateRoutine',
    method: 'put',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/routines/{routineId}',
    requestHeaders: ['X-Baton-Access-Key'],
    statuses: ['200', '404', '409'],
    summary: '루틴 수정',
  },
  {
    body: true,
    id: 'updateRoutineCompletion',
    method: 'patch',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/routines/{routineId}/completion',
    requestHeaders: ['X-Baton-Access-Key'],
    statuses: ['200', '404', '409'],
    summary: '루틴 완료 상태 변경',
  },
  {
    body: true,
    id: 'createDecision',
    method: 'post',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/decisions',
    requestHeaders: ['Idempotency-Key', 'X-Baton-Access-Key'],
    statuses: ['201'],
    summary: '결정 생성',
  },
  {
    body: true,
    id: 'createHandoffItem',
    method: 'post',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/handoff-items',
    requestHeaders: ['Idempotency-Key', 'X-Baton-Access-Key'],
    statuses: ['201'],
    summary: '인수인계 항목 생성',
  },
  {
    body: true,
    id: 'updateHandoffItemCompletion',
    method: 'patch',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/handoff-items/{itemId}/completion',
    requestHeaders: ['X-Baton-Access-Key'],
    statuses: ['200'],
    summary: '인수인계 항목 완료 상태 변경',
  },
]
const HTTP_METHODS = ['delete', 'get', 'head', 'options', 'patch', 'post', 'put', 'trace']

const inputPath = resolve(process.argv[2] ?? '')
const document = load(readFileSync(inputPath, 'utf8'))
const failures = []

function sameValues(actual, expected) {
  return [...actual].sort().join('\u0000') === [...expected].sort().join('\u0000')
}

function requiredParameters(operation, location) {
  return (operation.parameters ?? [])
    .filter((parameter) => parameter.in === location && parameter.required)
    .map((parameter) => parameter.name)
}

for (const expected of CONTRACT) {
  const operation = document.paths?.[expected.path]?.[expected.method]
  if (!operation) {
    failures.push(`${expected.method.toUpperCase()} ${expected.path} is missing`)
    continue
  }

  if (operation.operationId !== expected.id) {
    failures.push(`${expected.path} operationId: ${operation.operationId} != ${expected.id}`)
  }
  if (operation.summary !== expected.summary) {
    failures.push(`${expected.id} summary: ${operation.summary} != ${expected.summary}`)
  }
  if (Boolean(operation.requestBody) !== Boolean(expected.body)) {
    failures.push(`${expected.id} requestBody presence is incorrect`)
  }
  if (expected.body && operation.requestBody?.required !== true) {
    failures.push(`${expected.id} requestBody must be required`)
  }

  const expectedPathParameters = [...expected.path.matchAll(/\{([^}]+)}/g)].map((match) => match[1])
  const actualPathParameters = requiredParameters(operation, 'path')
  if (!sameValues(actualPathParameters, expectedPathParameters)) {
    failures.push(`${expected.id} path parameters are incorrect`)
  }

  const actualRequestHeaders = requiredParameters(operation, 'header')
  if (!sameValues(actualRequestHeaders, expected.requestHeaders ?? [])) {
    failures.push(`${expected.id} required request headers are incorrect`)
  }

  const actualStatuses = Object.keys(operation.responses ?? {})
  if (!sameValues(actualStatuses, expected.statuses)) {
    failures.push(`${expected.id} response statuses are incorrect`)
  }

  const successResponse = operation.responses?.[expected.statuses[0]]
  const actualResponseHeaders = Object.keys(successResponse?.headers ?? {})
  if (!sameValues(actualResponseHeaders, expected.responseHeaders ?? [])) {
    failures.push(`${expected.id} success response headers are incorrect`)
  }
}

const operationCount = Object.values(document.paths ?? {}).reduce(
  (count, pathItem) => count + HTTP_METHODS.filter((method) => pathItem?.[method]).length,
  0,
)

if (operationCount !== CONTRACT.length) failures.push(`operation count: ${operationCount} != ${CONTRACT.length}`)
if (document.servers?.[0]?.url !== '/') failures.push('OpenAPI server must be same-origin /')
if (!document.components?.schemas?.ErrorResponse) failures.push('ErrorResponse component is missing')

if (failures.length > 0) {
  failures.forEach((failure) => console.error(`- ${failure}`))
  process.exit(1)
}

console.log(`Validated ${CONTRACT.length} OpenAPI operations`)
