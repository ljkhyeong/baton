import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { readFile, readdir, rm } from 'node:fs/promises'
import { test } from 'node:test'
import { build, resolveConfig } from 'vite'

process.env.NODE_ENV = 'production'

test('소스맵 업로드에 필요한 설정이 없으면 빌드를 중단한다', async () => {
  process.env.SENTRY_SOURCEMAPS_UPLOAD = 'true'
  delete process.env.SENTRY_AUTH_TOKEN
  await assert.rejects(resolveConfig({}, 'build'), /SENTRY_AUTH_TOKEN 설정이 필요합니다/)
  process.env.NODE_ENV = 'development'
  await assert.rejects(resolveConfig({}, 'build'), /production 빌드에서만 실행합니다/)
  process.env.NODE_ENV = 'production'
})

test('소스맵 업로드 후 원본 맵을 제거하고 업로드 실패 시 빌드를 중단한다', { timeout: 60000 }, async (t) => {
  const calls = []
  const uploaded = new Set()
  let rejectUpload = false
  const server = createServer(async (request, response) => {
    const chunks = []
    for await (const chunk of request) chunks.push(chunk)
    const body = Buffer.concat(chunks)
    const path = new URL(request.url, 'http://localhost').pathname
    calls.push(`${request.method} ${path}`)
    response.setHeader('Content-Type', 'application/json')
    if (rejectUpload) {
      response.statusCode = 401
      response.end('{}')
      return
    }
    if (request.method === 'GET' && path === '/api/0/organizations/baton-test/chunk-upload/') {
      response.end(JSON.stringify({
        url: `http://127.0.0.1:${server.address().port}/chunks/`,
        chunkSize: 8388608, chunksPerRequest: 64, maxFileSize: 2147483648, maxRequestSize: 33554432,
        concurrency: 1, hashAlgorithm: 'sha1', compression: ['gzip'],
        accept: ['release_files', 'artifact_bundles', 'artifact_bundles_v2'],
      }))
    } else if (request.method === 'POST' && path === '/api/0/organizations/baton-test/artifactbundle/assemble/') {
      const bundle = JSON.parse(body)
      const missing = bundle.chunks.filter((hash) => !uploaded.has(hash))
      response.end(JSON.stringify({ state: missing.length ? 'not_found' : 'ok', missingChunks: missing }))
    } else if (request.method === 'POST' && path === '/chunks/') {
      for (const match of body.toString('latin1').matchAll(/filename="([a-f0-9]{40})"/g)) uploaded.add(match[1])
      response.end('{}')
    } else {
      response.statusCode = 404
      response.end(JSON.stringify({ detail: '지원하지 않는 테스트 요청' }))
    }
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  t.after(() => new Promise((resolve) => server.close(resolve)))
  t.after(() => rm('dist', { recursive: true, force: true }))
  Object.assign(process.env, {
    SENTRY_SOURCEMAPS_UPLOAD: 'true', SENTRY_AUTH_TOKEN: 'test-build-token',
    SENTRY_ORG: 'baton-test', SENTRY_PROJECT: 'web-test',
    VITE_SENTRY_DSN: 'https://public@o0.ingest.sentry.io/1',
    SENTRY_URL: `http://127.0.0.1:${server.address().port}`,
  })
  await build({ logLevel: 'warn' })
  assert.ok(uploaded.size > 0, `업로드 요청 누락: ${calls.join(', ')}`)
  const files = await readdir('dist/assets')
  assert.equal(files.some((name) => name.endsWith('.map')), false)
  const javascript = await Promise.all(files.filter((name) => name.endsWith('.js')).map((name) => readFile(`dist/assets/${name}`, 'utf8')))
  assert.ok(javascript.some((source) => source.includes('_sentryDebugIds')))
  assert.equal(javascript.some((source) => /sourceMappingURL=|test-build-token/.test(source)), false)
  rejectUpload = true
  await assert.rejects(build({ logLevel: 'silent' }), /소스맵 업로드에 실패해 웹 빌드를 중단합니다/)
})
