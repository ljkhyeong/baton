import { createHash } from 'node:crypto'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { dump, load } from 'js-yaml'

const HTTP_METHODS = ['delete', 'get', 'head', 'options', 'patch', 'post', 'put', 'trace']
const WATCH_HEALTH_EVENT_PATH = '/api/v1/internal/resource-health-events'
const ROUND_ROOM_MAPPINGS_PATH = '/api/v1/round-room-mappings'
const ROUND_PARTICIPATION_REFRESH_PATH = '/round/rooms/{roomId}/participation-grant/refresh'
const NON_UUID_PATH_PARAMETERS = new Set(['roomId'])
const ROUND_ROOM_ID_SCHEMA = {
  maxLength: 14,
  minLength: 14,
  pattern: '^[abcdefghjkmnpqrstuvwxyz23456789]{4}-[abcdefghjkmnpqrstuvwxyz23456789]{4}-[abcdefghjkmnpqrstuvwxyz23456789]{4}$',
  type: 'string',
}
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

let requestBodyCount = 0
const operationIds = new Set()
const responseSchemas = []

for (const [path, pathItem] of Object.entries(document.paths)) {
  for (const method of HTTP_METHODS) {
    const operation = pathItem?.[method]
    if (!operation) continue

    if (!operation.operationId || operationIds.has(operation.operationId)) {
      throw new Error(`Missing or duplicate operationId: ${operation.operationId ?? '<empty>'}`)
    }
    operationIds.add(operation.operationId)

    if (operation.requestBody) {
      operation.requestBody.required = path !== ROUND_PARTICIPATION_REFRESH_PATH
      requestBodyCount += 1
    }

    for (const parameter of operation.parameters ?? []) {
      if (parameter.in === 'path' && parameter.name === 'roomId') {
        parameter.schema = { ...parameter.schema, ...ROUND_ROOM_ID_SCHEMA }
        delete parameter.schema.format
      }
      if (
        (parameter.in === 'path' || parameter.in === 'query')
        && parameter.name.endsWith('Id')
        && !NON_UUID_PATH_PARAMETERS.has(parameter.name)
      ) {
        parameter.schema = { ...parameter.schema, format: 'uuid' }
      }
      if (parameter.in === 'header' && parameter.name === 'Idempotency-Key') {
        parameter.schema = path === WATCH_HEALTH_EVENT_PATH
          ? { format: 'uuid', type: 'string' }
          : {
              ...parameter.schema,
              maxLength: 200,
              minLength: 32,
              pattern: '^[A-Za-z0-9._~-]+$',
            }
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

const watchHealthEventRequestSchema = resolveSchema(
  document.paths?.[WATCH_HEALTH_EVENT_PATH]?.post?.requestBody?.content?.['application/json']?.schema,
)
if (!watchHealthEventRequestSchema) {
  throw new Error('WATCH health event request schema is missing')
}
watchHealthEventRequestSchema.additionalProperties = false
watchHealthEventRequestSchema.properties.eventType = {
  ...watchHealthEventRequestSchema.properties.eventType,
  enum: ['RESOURCE_HEALTH_CHANGED'],
  pattern: '^RESOURCE_HEALTH_CHANGED$',
}
watchHealthEventRequestSchema.properties.resourceReference = {
  ...watchHealthEventRequestSchema.properties.resourceReference,
  maxLength: 128,
}
watchHealthEventRequestSchema.properties.sourceRevision = {
  ...watchHealthEventRequestSchema.properties.sourceRevision,
  format: 'int64',
  minimum: 0,
  type: 'integer',
}

const roundParticipationRefreshRequestSchema = resolveSchema(
  document.paths?.[ROUND_PARTICIPATION_REFRESH_PATH]?.post
    ?.requestBody?.content?.['application/json']?.schema,
)
if (!roundParticipationRefreshRequestSchema) {
  throw new Error('ROUND participation refresh request schema is missing')
}
roundParticipationRefreshRequestSchema.additionalProperties = false

const localRegistrationRequestSchema = resolveSchema(
  document.paths?.['/api/v1/auth/local/registrations']?.post
    ?.requestBody?.content?.['application/json']?.schema,
)
if (!localRegistrationRequestSchema) {
  throw new Error('Local registration request schema is missing')
}
localRegistrationRequestSchema.properties.email = {
  ...localRegistrationRequestSchema.properties.email,
  format: 'email',
}

const localEmailVerificationRequestSchema = resolveSchema(
  document.paths?.['/api/v1/auth/local/email-verifications']?.post
    ?.requestBody?.content?.['application/json']?.schema,
)
if (!localEmailVerificationRequestSchema) {
  throw new Error('Local email verification request schema is missing')
}
localEmailVerificationRequestSchema.properties.password = {
  ...localEmailVerificationRequestSchema.properties.password,
  minLength: 12,
}

const authProvidersResponseSchema = resolveSchema(
  document.paths?.['/api/v1/auth/providers']?.get
    ?.responses?.['200']?.content?.['application/json']?.schema,
)
if (!authProvidersResponseSchema) {
  throw new Error('Auth providers response schema is missing')
}
authProvidersResponseSchema.properties.providers.items = {
  enum: ['google', 'naver'],
  type: 'string',
}

const authSessionResponseSchema = resolveSchema(
  document.paths?.['/api/v1/auth/session']?.get
    ?.responses?.['200']?.content?.['application/json']?.schema,
)
if (!authSessionResponseSchema) {
  throw new Error('Auth session response schema is missing')
}
Object.keys(authSessionResponseSchema).forEach((key) => delete authSessionResponseSchema[key])
authSessionResponseSchema.oneOf = [
  {
    additionalProperties: false,
    properties: {
      authenticated: { enum: [false], type: 'boolean' },
    },
    required: ['authenticated'],
    type: 'object',
  },
  {
    additionalProperties: false,
    properties: {
      accountId: { format: 'uuid', type: 'string' },
      authenticated: { enum: [true], type: 'boolean' },
      csrfHeaderName: { type: 'string' },
      csrfToken: { type: 'string' },
    },
    required: ['accountId', 'authenticated', 'csrfHeaderName', 'csrfToken'],
    type: 'object',
  },
]

const currentMembershipResponseSchema = resolveSchema(
  document.paths?.['/api/v1/account-memberships/current']?.get
    ?.responses?.['200']?.content?.['application/json']?.schema,
)
if (!currentMembershipResponseSchema) {
  throw new Error('Current account membership response schema is missing')
}
Object.keys(currentMembershipResponseSchema)
  .forEach((key) => delete currentMembershipResponseSchema[key])
currentMembershipResponseSchema.oneOf = [
  {
    additionalProperties: false,
    properties: {
      claimed: { enum: [false], type: 'boolean' },
    },
    required: ['claimed'],
    type: 'object',
  },
  {
    additionalProperties: false,
    properties: {
      accountId: { format: 'uuid', type: 'string' },
      claimed: { enum: [true], type: 'boolean' },
      claimedAt: { format: 'date-time', type: 'string' },
      memberId: { format: 'uuid', type: 'string' },
      teamId: { format: 'uuid', type: 'string' },
    },
    required: ['accountId', 'claimed', 'claimedAt', 'memberId', 'teamId'],
    type: 'object',
  },
]

const currentRoomMappingResponseSchema = resolveSchema(
  document.paths?.[ROUND_ROOM_MAPPINGS_PATH]?.get
    ?.responses?.['200']?.content?.['application/json']?.schema,
)
if (!currentRoomMappingResponseSchema) {
  throw new Error('Current ROUND room mapping response schema is missing')
}
Object.keys(currentRoomMappingResponseSchema)
  .forEach((key) => delete currentRoomMappingResponseSchema[key])
currentRoomMappingResponseSchema.oneOf = [
  {
    additionalProperties: false,
    properties: {
      mapped: { enum: [false], type: 'boolean' },
    },
    required: ['mapped'],
    type: 'object',
  },
  {
    additionalProperties: false,
    properties: {
      createdAt: { format: 'date-time', type: 'string' },
      endedAt: { format: 'date-time', nullable: true, type: 'string' },
      mapped: { enum: [true], type: 'boolean' },
      resourceId: { format: 'uuid', type: 'string' },
      roomId: ROUND_ROOM_ID_SCHEMA,
      seasonId: { format: 'uuid', type: 'string' },
      teamId: { format: 'uuid', type: 'string' },
    },
    required: [
      'createdAt',
      'endedAt',
      'mapped',
      'resourceId',
      'roomId',
      'seasonId',
      'teamId',
    ],
    type: 'object',
  },
]

const roundParticipationRefreshResponseSchema = resolveSchema(
  document.paths?.[ROUND_PARTICIPATION_REFRESH_PATH]?.post
    ?.responses?.['200']?.content?.['application/json']?.schema,
)
if (!roundParticipationRefreshResponseSchema) {
  throw new Error('ROUND participation refresh response schema is missing')
}
roundParticipationRefreshResponseSchema.properties.expiresAt = {
  ...roundParticipationRefreshResponseSchema.properties.expiresAt,
  format: 'int64',
  type: 'integer',
}
roundParticipationRefreshResponseSchema.properties.refreshAfterSeconds = {
  ...roundParticipationRefreshResponseSchema.properties.refreshAfterSeconds,
  format: 'int32',
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
    if (propertyName === 'roomId') {
      Object.assign(schema, ROUND_ROOM_ID_SCHEMA)
      delete schema.format
    } else if (propertyName === 'id' || propertyName?.endsWith('Id')) {
      schema.format = 'uuid'
    }
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
