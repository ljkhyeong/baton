import { ApiClientError, ApiError } from '@/shared/api/ApiError'
import type { ErrorResponse } from '@/shared/types/ErrorResponse'

const DEFAULT_TIMEOUT_MS = 10_000
const UNKNOWN_ERROR_RESPONSE = {
  code: 'UNKNOWN_ERROR',
  message: '요청을 처리하지 못했습니다.',
} satisfies ErrorResponse

export type ResponseDecoder<T> = (value: unknown) => T

type RequestOptions<T> = Omit<RequestInit, 'body'> & {
  body?: unknown
  decode: ResponseDecoder<T>
  query?: Record<string, boolean | number | string | null | undefined>
  timeoutMs?: number
}

function buildUrl(path: string, query?: RequestOptions<unknown>['query']) {
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
    if (typeof body !== 'object' || body === null) return UNKNOWN_ERROR_RESPONSE
    const errorBody = body as Record<string, unknown>
    return {
      code: typeof errorBody.code === 'string' && errorBody.code
        ? errorBody.code
        : UNKNOWN_ERROR_RESPONSE.code,
      message: typeof errorBody.message === 'string' && errorBody.message
        ? errorBody.message
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

export async function apiRequest<T>(path: string, options: RequestOptions<T>): Promise<T> {
  const {
    body,
    decode,
    headers,
    query,
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
    response = await fetch(buildUrl(path, query), {
      ...requestInit,
      body: requestBody,
      credentials: 'same-origin',
      headers: {
        Accept: 'application/json',
        ...(body === undefined
          ? {}
          : {
              'Content-Type': formBody
                ? 'application/x-www-form-urlencoded;charset=UTF-8'
                : 'application/json',
            }),
        ...headers,
      },
      signal: requestSignal,
    })
  } catch (error) {
    throwTransportError(error, timeoutSignal, externalSignal)
  }

  if (!response.ok) {
    throw new ApiError(
      response.status,
      await parseError(response, timeoutSignal, externalSignal),
      response.headers.get('X-Request-ID'),
    )
  }
  if (response.status === 204) {
    try {
      return decode(undefined)
    } catch (error) {
      throw new ApiClientError('invalid-response', error)
    }
  }
  let responseBody: unknown
  try {
    responseBody = await response.json()
  } catch (error) {
    if (timeoutSignal.aborted || externalSignal?.aborted) {
      throwTransportError(error, timeoutSignal, externalSignal)
    }
    throw new ApiClientError(error instanceof SyntaxError ? 'invalid-response' : 'network', error)
  }

  try {
    return decode(responseBody)
  } catch (error) {
    throw new ApiClientError('invalid-response', error)
  }
}
