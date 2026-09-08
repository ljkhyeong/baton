import { ApiClientError, ApiError } from '@/shared/api/ApiError'
import { isJsonObject } from '@/shared/api/responseValidation'
import type { ErrorResponse } from '@/shared/types/ErrorResponse'

const DEFAULT_TIMEOUT_MS = 10_000
const UNKNOWN_ERROR_RESPONSE = {
  code: 'UNKNOWN_ERROR',
  message: '요청을 처리하지 못했습니다.',
} satisfies ErrorResponse

type ResponseDecoder<T> = (value: unknown) => T

type BaseRequestOptions = Omit<RequestInit, 'body'> & {
  body?: unknown
  query?: Record<string, boolean | number | string | null | undefined>
  timeoutMs?: number
}

export type ContentRequestOptions<T> = BaseRequestOptions & {
  decode: ResponseDecoder<T>
  responseType?: 'json'
}

type NoContentRequestOptions = BaseRequestOptions & {
  decode?: never
  responseType: 'no-content'
}

type RequestOptions<T> = ContentRequestOptions<T> | NoContentRequestOptions

function buildUrl(path: string, query?: BaseRequestOptions['query']) {
  if (!query) return path

  const search = new URLSearchParams()
  Object.entries(query).forEach(([key, value]) => {
    if (value !== null && value !== undefined) search.set(key, String(value))
  })

  const serialized = search.toString()
  return serialized ? `${path}?${serialized}` : path
}

function throwTransportError(
  error: unknown,
  timeoutSignal: AbortSignal,
  externalSignal?: AbortSignal | null,
): never {
  if (timeoutSignal.aborted) throw new ApiClientError('timeout', error)
  if (externalSignal?.aborted) throw error
  throw new ApiClientError('network', error)
}

async function parseError(
  response: Response,
  timeoutSignal: AbortSignal,
  externalSignal?: AbortSignal | null,
): Promise<ErrorResponse> {
  try {
    const body: unknown = await response.json()
    if (!isJsonObject(body)) return UNKNOWN_ERROR_RESPONSE
    return {
      code: typeof body.code === 'string' && body.code
        ? body.code
        : UNKNOWN_ERROR_RESPONSE.code,
      message: typeof body.message === 'string' && body.message
        ? body.message
        : UNKNOWN_ERROR_RESPONSE.message,
    }
  } catch (error) {
    if (timeoutSignal.aborted || externalSignal?.aborted) {
      throwTransportError(error, timeoutSignal, externalSignal)
    }
    if (!(error instanceof SyntaxError)) throw new ApiClientError('network', error)
    return UNKNOWN_ERROR_RESPONSE
  }
}

export function apiRequest<T>(path: string, options: ContentRequestOptions<T>): Promise<T>
export function apiRequest(path: string, options: NoContentRequestOptions): Promise<void>
export async function apiRequest<T>(
  path: string,
  options: RequestOptions<T>,
): Promise<T | void> {
  const {
    body,
    decode,
    headers,
    query,
    responseType,
    signal: externalSignal,
    timeoutMs = DEFAULT_TIMEOUT_MS,
    ...requestInit
  } = options
  const formBody = body instanceof URLSearchParams
  const requestBody = body === undefined
    ? undefined
    : formBody
      ? body
      : JSON.stringify(body)
  const timeoutSignal = AbortSignal.timeout(timeoutMs)
  const requestSignal = externalSignal
    ? AbortSignal.any([timeoutSignal, externalSignal])
    : timeoutSignal

  let response: Response
  try {
    const requestHeaders = new Headers(headers)
    if (!requestHeaders.has('Accept')) {
      requestHeaders.set('Accept', 'application/json')
    }
    if (body !== undefined && !formBody && !requestHeaders.has('Content-Type')) {
      requestHeaders.set('Content-Type', 'application/json')
    }
    response = await fetch(buildUrl(path, query), {
      ...requestInit,
      body: requestBody,
      credentials: 'same-origin',
      headers: requestHeaders,
      signal: requestSignal,
    })
    // 응답 대기 중 취소된 요청의 늦은 응답을 성공으로 처리하지 않는다.
    requestSignal.throwIfAborted()
  } catch (error) {
    throwTransportError(error, timeoutSignal, externalSignal)
  }

  if (!response.ok) {
    const retryAfter = response.headers.get('Retry-After')
    const retryAfterSeconds = retryAfter !== null && /^\d+$/.test(retryAfter)
      && Number.isSafeInteger(Number(retryAfter)) && Number(retryAfter) > 0
      ? Math.min(Number(retryAfter), 3_600) : undefined
    throw new ApiError(
      response.status,
      await parseError(response, timeoutSignal, externalSignal),
      response.headers.get('X-Request-ID'),
      retryAfterSeconds,
    )
  }
  const hasNoContent = response.status === 204
  if (hasNoContent !== (responseType === 'no-content')) {
    throw new ApiClientError(
      'invalid-response',
      new Error('HTTP 성공 응답의 본문 계약이 예상과 다릅니다.'),
    )
  }
  if (responseType === 'no-content') return

  let responseBody: unknown
  try {
    responseBody = await response.json()
  } catch (error) {
    if (timeoutSignal.aborted || externalSignal?.aborted) {
      throwTransportError(error, timeoutSignal, externalSignal)
    }
    throw new ApiClientError('invalid-response', error)
  }

  try {
    return decode(responseBody)
  } catch (error) {
    throw new ApiClientError('invalid-response', error)
  }
}
