import { createHash } from 'node:crypto'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { dump, load } from 'js-yaml'

const HTTP_METHODS = ['delete', 'get', 'head', 'options', 'patch', 'post', 'put', 'trace']
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

for (const pathItem of Object.values(document.paths)) {
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
    if (['getMe', 'acceptInvitation', 'logoutSession'].includes(operation.operationId)) {
      operation.security = [{ batonSession: [] }]
    }
    if (operation.operationId === 'issueBootstrapInvitation') {
      operation.security = [{ identityBootstrapKey: [] }]
    }

    if (operation.requestBody) {
      operation.requestBody.required = true
      requestBodyCount += 1
    }

    for (const parameter of operation.parameters ?? []) {
      if (parameter.in === 'path' && parameter.name.endsWith('Id')) {
        parameter.schema = { ...parameter.schema, format: 'uuid' }
      }
      if (parameter.in === 'header' && parameter.name === 'Idempotency-Key') {
        parameter.schema = ['issueBootstrapInvitation', 'openRoleResourceLink']
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
        && ['acceptInvitation', 'logoutSession'].includes(operation.operationId)
      ) {
        parameter.description = '현재 인증 세션에 결속된 CSRF 토큰'
        parameter.required = true
      }
    }

    for (const response of Object.values(operation.responses ?? {})) {
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
    '운영에서는 __Host-baton_session; Secure; SameSite=Lax; Path=/를 사용하고',
    '로컬 개발에서는 secure가 아닌 baton_session 이름을 사용한다.',
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
