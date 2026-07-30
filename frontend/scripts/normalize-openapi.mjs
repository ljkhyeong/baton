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

    if (operation.requestBody) {
      operation.requestBody.required = true
      requestBodyCount += 1
    }

    for (const parameter of operation.parameters ?? []) {
      if (parameter.in === 'path' && parameter.name.endsWith('Id')) {
        parameter.schema = { ...parameter.schema, format: 'uuid' }
      }
      if (parameter.in === 'header' && parameter.name === 'Idempotency-Key') {
        parameter.schema = {
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
