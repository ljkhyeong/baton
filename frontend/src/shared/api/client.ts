import { ApiClientError, ApiError } from '@/shared/api/ApiError'
import type { ErrorResponse } from '@/shared/types/ErrorResponse'

const DEFAULT_TIMEOUT_MS = 10_000
const UNKNOWN_ERROR_RESPONSE = {
  code: 'UNKNOWN_ERROR',
  message: '요청을 처리하지 못했습니다.',
} satisfies ErrorResponse

type RequestOptions = Omit<RequestInit, 'body'> & {
  body?: unknown
  query?: Record<string, boolean | number | string | null | undefined>
  timeoutMs?: number
}

function buildUrl(path: string, query?: RequestOptions['query']) {
  if (!query) return path

  const search = new URLSearchParams()
  Object.entries(query).forEach(([key, value]) => {
    if (value !== null && value !== undefined) search.set(key, String(value))
  })

  const serialized = search.toString()
  return serialized ? `${path}?${serialized}` : path
}

async function parseError(response: Response, signal: AbortSignal): Promise<ErrorResponse> {
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
    if (signal.aborted) throw new ApiClientError('timeout', error)
    if (!(error instanceof SyntaxError)) throw new ApiClientError('network', error)
    return UNKNOWN_ERROR_RESPONSE
  }
}

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { body, headers, query, timeoutMs = DEFAULT_TIMEOUT_MS, ...requestInit } = options
  const requestBody = body === undefined ? undefined : JSON.stringify(body)
  const abortController = new AbortController()
  const timeout = window.setTimeout(() => abortController.abort(), timeoutMs)

  try {
    let response: Response
    try {
      response = await fetch(buildUrl(path, query), {
        ...requestInit,
        body: requestBody,
        credentials: 'same-origin',
        headers: {
          Accept: 'application/json',
          ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
          ...headers,
        },
        signal: abortController.signal,
      })
    } catch (error) {
      throw new ApiClientError(abortController.signal.aborted ? 'timeout' : 'network', error)
    }

    if (!response.ok) {
      throw new ApiError(
        response.status,
        await parseError(response, abortController.signal),
      )
    }
    if (response.status === 204) return undefined as T
    try {
      return (await response.json()) as T
    } catch (error) {
      throw new ApiClientError(
        abortController.signal.aborted
          ? 'timeout'
          : error instanceof SyntaxError
            ? 'invalid-response'
            : 'network',
        error,
      )
    }
  } finally {
    window.clearTimeout(timeout)
  }
}
