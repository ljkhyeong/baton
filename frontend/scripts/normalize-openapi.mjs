import { createHash } from 'node:crypto'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { dump, load } from 'js-yaml'

const HTTP_METHODS = ['delete', 'get', 'head', 'options', 'patch', 'post', 'put', 'trace']
const ROUND_GRANT_REFRESH_OPERATION_ID = 'refreshRoundParticipationGrant'
const [inputArgument, outputArgument] = process.argv.slice(2)

if (!inputArgument || !outputArgument) {
  throw new Error('Usage: node scripts/normalize-openapi.mjs <input> <output>')
}

const inputPath = resolve(inputArgument)
const outputPath = resolve(outputArgument)
const document = load(readFileSync(inputPath, 'utf8'))

if (!document || typeof document !== 'object' || !document.paths) {
  throw new Error('OpenAPI document does not contain paths')
}

const responseHeader = (description) => ({
  description,
  schema: { type: 'string' },
})
const requestIdHeader = responseHeader('서버가 생성한 불투명 요청 진단 식별자')
const noStoreHeader = responseHeader('신원·세션 응답을 저장하지 않도록 하는 no-store 지시자')
const locationHeader = responseHeader('브라우저가 이동할 다음 경로')
const errorResponse = (description) => ({
  description,
  content: {
    'application/json': {
      schema: { $ref: '#/components/schemas/ErrorResponse' },
    },
  },
  headers: {
    'Cache-Control': noStoreHeader,
    'X-Request-ID': requestIdHeader,
  },
})
const edgeResponse = (description) => ({
  description,
  headers: {
    'Cache-Control': noStoreHeader,
    'X-Request-ID': requestIdHeader,
  },
})

function addFilterOperation(path, operation) {
  document.paths[path] ??= {}
  if (document.paths[path].get) {
    throw new Error(`OpenAPI already contains filter operation: GET ${path}`)
  }
  document.paths[path].get = operation
}

addFilterOperation('/api/v1/auth/oidc/authorization/google', {
  description: 'Authorization Code + PKCE를 시작하고 Google authorization endpoint로 이동한다.',
  operationId: 'authorizeGoogleOidc',
  responses: {
    302: {
      description: 'Google authorization endpoint로 이동',
      headers: {
        'Cache-Control': noStoreHeader,
        Location: locationHeader,
        'X-Request-ID': requestIdHeader,
      },
    },
  },
  security: [],
  summary: 'Google OIDC 로그인 시작',
  tags: ['api'],
})

addFilterOperation('/api/v1/auth/oidc/callback/google', {
  description: 'Google OIDC callback을 검증하고 성공하면 BATON session을 만든 뒤 홈으로 이동한다.',
  operationId: 'handleGoogleOidcCallback',
  responses: {
    302: {
      description: '로그인 성공 후 홈으로 이동',
      headers: {
        'Cache-Control': noStoreHeader,
        Location: locationHeader,
        'X-Request-ID': requestIdHeader,
      },
    },
    401: errorResponse('OIDC 로그인 실패'),
  },
  security: [],
  summary: 'Google OIDC callback 처리',
  tags: ['api'],
})

let requestBodyCount = 0
const operationIds = new Set()
const responseSchemas = []
const sensitiveHeaderNames = new Set([
  'authorization',
  'cookie',
  'set-cookie',
  'x-baton-access-key',
  'x-baton-creation-key',
  'x-baton-identity-bootstrap-key',
  'x-baton-recovery-key',
  'x-csrf-token',
])

function redactCsrfTokenInJsonExample(value) {
  if (typeof value !== 'string') return value
  return value.replace(
    /("csrfToken"\s*:\s*")[^"]*(")/g,
    '$1<redacted>$2',
  )
}

for (const [path, pathItem] of Object.entries(document.paths)) {
  for (const method of HTTP_METHODS) {
    const operation = pathItem?.[method]
    if (!operation) continue

    if (!operation.operationId || operationIds.has(operation.operationId)) {
      throw new Error(`Missing or duplicate operationId: ${operation.operationId ?? '<empty>'}`)
    }
    operationIds.add(operation.operationId)

    if (operation.operationId === 'getIdentitySession') {
      operation.security = [{}, { batonSession: [] }]
    }
    if (
      [
        'getMe',
        'getTeamMembership',
        'previewInvitation',
        'acceptInvitation',
        'issueMemberInvitation',
        'listMemberInvitations',
        'revokeMemberInvitation',
        'logoutSession',
        'issueRoundParticipationGrant',
        'issueRoundRoomParticipationGrant',
        ROUND_GRANT_REFRESH_OPERATION_ID,
      ].includes(operation.operationId)
    ) {
      operation.security = [{ batonSession: [] }]
    }
    if (operation.operationId === 'getRoundJwkSet') {
      operation.security = []
    }
    if (operation.operationId === 'issueBootstrapInvitation') {
      operation.security = [{ identityBootstrapKey: [] }]
    }

    const teamSeasonWorkspacePath =
      path.startsWith('/api/v1/teams/{teamId}/seasons/{seasonId}')
    const sessionOnlyRoundGrant = [
      'issueRoundParticipationGrant',
      'issueRoundRoomParticipationGrant',
      ROUND_GRANT_REFRESH_OPERATION_ID,
    ].includes(operation.operationId)
    const legacyAccessKeyRotation = operation.operationId === 'rotateAccessKey'
    const operatorAccessKeyRecovery = operation.operationId === 'recoverAccessKey'
    const dualWorkspaceAuthorization =
      teamSeasonWorkspacePath
      && !sessionOnlyRoundGrant
      && !legacyAccessKeyRotation
      && !operatorAccessKeyRecovery

    if (dualWorkspaceAuthorization) {
      operation.security = [
        { batonSession: [] },
        { legacyWorkspaceAccessKey: [] },
      ]
    }
    if (legacyAccessKeyRotation) {
      operation.security = [{ legacyWorkspaceAccessKey: [] }]
    }
    if (
      dualWorkspaceAuthorization
      && ['post', 'put', 'patch', 'delete'].includes(method)
      && !(operation.parameters ?? []).some((parameter) =>
        parameter.in === 'header' && parameter.name === 'X-CSRF-TOKEN')
    ) {
      operation.parameters ??= []
      operation.parameters.push({
        description: [
          'session 방식 또는 로그인 session과 함께 쓰는 레거시 방식의 동적 CSRF 토큰.',
          'session이 없는 레거시 header 요청에는 생략한다.',
        ].join(' '),
        in: 'header',
        name: 'X-CSRF-TOKEN',
        required: false,
        schema: { type: 'string' },
      })
    }

    if (operation.requestBody) {
      operation.requestBody.required =
        operation.operationId !== ROUND_GRANT_REFRESH_OPERATION_ID
      requestBodyCount += 1
    }

    for (const parameter of operation.parameters ?? []) {
      if (
        parameter.in === 'header'
        && sensitiveHeaderNames.has(String(parameter.name).toLowerCase())
      ) {
        delete parameter.example
        if (parameter.schema) delete parameter.schema.example
      }
      if (
        dualWorkspaceAuthorization
        && parameter.in === 'header'
        && parameter.name === 'X-Baton-Access-Key'
      ) {
        parameter.description = [
          '명시적 레거시 workspace 권한에만 사용하는 공유 접근 키.',
          'session 방식에서는 생략하며 잘못된 값을 session으로 fallback하지 않는다.',
        ].join(' ')
        parameter.required = false
      }
      if (
        parameter.in === 'path'
        && parameter.name === 'roomId'
        && [
          'issueRoundRoomParticipationGrant',
          ROUND_GRANT_REFRESH_OPERATION_ID,
        ].includes(operation.operationId)
      ) {
        parameter.schema = {
          type: 'string',
          minLength: 14,
          maxLength: 14,
          pattern:
            '^[abcdefghjkmnpqrstuvwxyz23456789]{4}'
            + '(?:-[abcdefghjkmnpqrstuvwxyz23456789]{4}){2}$',
        }
      } else if (parameter.in === 'path' && parameter.name.endsWith('Id')) {
        parameter.schema = { ...parameter.schema, format: 'uuid' }
      }
      if (parameter.in === 'header' && parameter.name === 'Idempotency-Key') {
        parameter.schema = [
          'issueBootstrapInvitation',
          'issueMemberInvitation',
          'openRoleResourceLink',
        ]
          .includes(operation.operationId)
          ? {
              ...parameter.schema,
              format: 'uuid',
              maxLength: 36,
              minLength: 36,
              pattern: '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
            }
          : {
              ...parameter.schema,
              maxLength: 200,
              minLength: 32,
              pattern: '^[A-Za-z0-9._~-]+$',
            }
      }
      if (
        parameter.in === 'header'
        && parameter.name === 'X-CSRF-TOKEN'
        && [
          'previewInvitation',
          'acceptInvitation',
          'issueMemberInvitation',
          'revokeMemberInvitation',
          'logoutSession',
        ].includes(operation.operationId)
      ) {
        parameter.description = '현재 인증 세션에 결속된 CSRF 토큰'
        parameter.required = true
      }
    }

    for (const response of Object.values(operation.responses ?? {})) {
      for (const mediaType of Object.values(response?.content ?? {})) {
        for (const example of Object.values(mediaType?.examples ?? {})) {
          if (example && 'value' in example) {
            example.value = redactCsrfTokenInJsonExample(example.value)
          }
        }
      }
      const schema = response?.content?.['application/json']?.schema
      if (schema) responseSchemas.push(schema)
    }
  }
}

if (requestBodyCount === 0) throw new Error('OpenAPI document does not contain request bodies')

const schemas = document.components?.schemas ?? {}

function resolveSchema(schema) {
  const reference = schema?.$ref
  if (!reference) return schema
  const prefix = '#/components/schemas/'
  if (!reference.startsWith(prefix)) return schema
  return schemas[reference.slice(prefix.length)]
}

const refreshOperation =
  document.paths?.['/round/rooms/{roomId}/participation-grant/refresh']?.post
if (refreshOperation?.operationId !== ROUND_GRANT_REFRESH_OPERATION_ID) {
  throw new Error('ROUND participation grant refresh operation is missing')
}
refreshOperation.responses['413'] ??= edgeResponse(
  'BATON edge가 허용한 1KB보다 큰 갱신 요청',
)
refreshOperation.responses['429'] ??= edgeResponse(
  'BATON edge의 room transport 요청 제한 초과',
)

const refreshRequestSchema = resolveSchema(
  refreshOperation.requestBody?.content?.['application/json']?.schema,
)
if (!refreshRequestSchema) {
  throw new Error('ROUND participation grant refresh request schema is missing')
}
refreshRequestSchema.additionalProperties = false
refreshRequestSchema.required = ['resourceId', 'seasonId', 'teamId']
for (const propertyName of refreshRequestSchema.required) {
  refreshRequestSchema.properties[propertyName] = {
    ...refreshRequestSchema.properties[propertyName],
    format: 'uuid',
    type: 'string',
  }
}

const refreshResponseSchema = resolveSchema(
  refreshOperation.responses?.['200']?.content?.['application/json']?.schema,
)
if (!refreshResponseSchema) {
  throw new Error('ROUND participation grant refresh response schema is missing')
}
refreshResponseSchema.additionalProperties = false
refreshResponseSchema.required = ['expiresAt', 'refreshAfterSeconds']
refreshResponseSchema.properties.expiresAt = {
  format: 'int64',
  minimum: 1,
  type: 'integer',
}
refreshResponseSchema.properties.refreshAfterSeconds = {
  format: 'int64',
  maximum: 300,
  minimum: 1,
  type: 'integer',
}

function makeNullableResponseFieldsRequired(schema, visited = new Set()) {
  const resolvedSchema = resolveSchema(schema)
  if (!resolvedSchema || visited.has(resolvedSchema)) return
  visited.add(resolvedSchema)

  if (resolvedSchema.properties) {
    const required = new Set(resolvedSchema.required ?? [])
    for (const [name, property] of Object.entries(resolvedSchema.properties)) {
      if (name === 'roundSchedule' && property?.type === 'object') {
        property.nullable = true
        property.required = Object.keys(property.properties ?? {}).sort()
        for (const scheduleProperty of Object.values(property.properties ?? {})) {
          delete scheduleProperty.nullable
        }
      }
      if (property?.nullable === true) required.add(name)
      makeNullableResponseFieldsRequired(property, visited)
    }
    if (required.size > 0) resolvedSchema.required = [...required].sort()
  }

  if (resolvedSchema.items) makeNullableResponseFieldsRequired(resolvedSchema.items, visited)
  for (const variant of resolvedSchema.allOf ?? []) makeNullableResponseFieldsRequired(variant, visited)
  for (const variant of resolvedSchema.anyOf ?? []) makeNullableResponseFieldsRequired(variant, visited)
  for (const variant of resolvedSchema.oneOf ?? []) makeNullableResponseFieldsRequired(variant, visited)
}

for (const responseSchema of responseSchemas) makeNullableResponseFieldsRequired(responseSchema)

function addStringFormats(schema, propertyName) {
  if (!schema || typeof schema !== 'object') return

  if (Array.isArray(schema.required)) schema.required.sort()

  if (schema.type === 'string') {
    if (propertyName === 'id' || propertyName?.endsWith('Id')) schema.format = 'uuid'
    if (propertyName?.endsWith('Date')) schema.format = 'date'
    if (propertyName?.endsWith('At')) schema.format = 'date-time'
  }
  if (schema.type === 'array' && propertyName?.endsWith('Ids') && schema.items?.type === 'string') {
    schema.items.format = 'uuid'
  }

  for (const [name, property] of Object.entries(schema.properties ?? {})) {
    addStringFormats(property, name)
  }
  if (schema.items) addStringFormats(schema.items)
}

for (const schema of Object.values(schemas)) addStringFormats(schema)

function stableValue(value) {
  if (Array.isArray(value)) return value.map(stableValue)
  if (!value || typeof value !== 'object') return value
  return Object.fromEntries(
    Object.keys(value)
      .sort()
      .map((key) => [key, stableValue(value[key])]),
  )
}

const renamedSchemas = {}
const schemaNames = new Map()

function preferredSchemaName(schema, digest) {
  const propertyNames = Object.keys(schema.properties ?? {}).sort()
  const requiredNames = [...(schema.required ?? [])].sort()
  if (
    propertyNames.join(',') === 'code,message' &&
    requiredNames.join(',') === 'code,message' &&
    schema.properties.code?.type === 'string' &&
    schema.properties.message?.type === 'string'
  ) {
    return 'ErrorResponse'
  }
  return `Schema_${digest}`
}

for (const [name, schema] of Object.entries(schemas)) {
  const digest = createHash('sha256')
    .update(JSON.stringify(stableValue(schema)))
    .digest('hex')
    .slice(0, 16)
  const normalizedName = preferredSchemaName(schema, digest)
  schemaNames.set(name, normalizedName)
  renamedSchemas[normalizedName] ??= schema
}

function replaceSchemaReferences(value) {
  if (Array.isArray(value)) {
    value.forEach(replaceSchemaReferences)
    return
  }
  if (!value || typeof value !== 'object') return

  if (typeof value.$ref === 'string') {
    const prefix = '#/components/schemas/'
    const oldName = value.$ref.startsWith(prefix) ? value.$ref.slice(prefix.length) : undefined
    if (oldName && schemaNames.has(oldName)) value.$ref = `${prefix}${schemaNames.get(oldName)}`
  }
  Object.values(value).forEach(replaceSchemaReferences)
}

replaceSchemaReferences(document)
document.components ??= {}
document.components.schemas = renamedSchemas
document.components.securitySchemes ??= {}
document.components.securitySchemes.batonSession = {
  type: 'apiKey',
  description: [
    '서버가 발급하고 폐기하는 host-only HttpOnly session cookie.',
    '운영에서는 __Host-baton_session; Secure; SameSite=Lax; Path=/을 사용하고',
    '로컬 개발에서는 secure가 아닌 baton_session 이름과 같은 path를 사용한다.',
    'ROUND edge는 이 cookie를 정적·signaling·TURN upstream에서 제거한다.',
  ].join(' '),
  in: 'cookie',
  name: '__Host-baton_session',
}
document.components.securitySchemes.identityBootstrapKey = {
  type: 'apiKey',
  description: '외부 edge에서 차단하고 신뢰한 내부 운영 경계에서만 사용하는 bootstrap 키',
  in: 'header',
  name: 'X-Baton-Identity-Bootstrap-Key',
}
document.components.securitySchemes.legacyWorkspaceAccessKey = {
  type: 'apiKey',
  description: [
    '기존 공유 링크 마이그레이션에만 사용하는 레거시 workspace bearer key.',
    '브라우저 영속 저장소에 보관하지 않고 session 권한으로 fallback하지 않는다.',
  ].join(' '),
  in: 'header',
  name: 'X-Baton-Access-Key',
}

const keyPriority = new Map(
  ['openapi', 'info', 'title', 'description', 'version', 'servers', 'url', 'paths', 'components', 'schemas']
    .map((key, index) => [key, index]),
)

function compareKeys(left, right) {
  const leftPriority = keyPriority.get(left) ?? keyPriority.size
  const rightPriority = keyPriority.get(right) ?? keyPriority.size
  return leftPriority === rightPriority ? left.localeCompare(right, 'en') : leftPriority - rightPriority
}

const normalized = dump(document, {
  lineWidth: 120,
  noRefs: true,
  sortKeys: compareKeys,
})

mkdirSync(dirname(outputPath), { recursive: true })
writeFileSync(outputPath, normalized, 'utf8')
