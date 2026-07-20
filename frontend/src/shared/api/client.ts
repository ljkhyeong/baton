import { ApiError } from '@/shared/api/ApiError'
import type { ErrorResponse } from '@/shared/types/ErrorResponse'

const DEFAULT_TIMEOUT_MS = 10_000

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

async function parseError(response: Response): Promise<ErrorResponse> {
  try {
    const body = (await response.json()) as Partial<ErrorResponse>
    return {
      code: body.code ?? 'UNKNOWN_ERROR',
      message: body.message ?? '요청을 처리하지 못했습니다.',
    }
  } catch {
    return { code: 'UNKNOWN_ERROR', message: '요청을 처리하지 못했습니다.' }
  }
}

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { body, headers, query, timeoutMs = DEFAULT_TIMEOUT_MS, ...requestInit } = options
  const abortController = new AbortController()
  const timeout = window.setTimeout(() => abortController.abort(), timeoutMs)

  try {
    const response = await fetch(buildUrl(path, query), {
      ...requestInit,
      body: body === undefined ? undefined : JSON.stringify(body),
      credentials: 'same-origin',
      headers: {
        Accept: 'application/json',
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
        ...headers,
      },
      signal: abortController.signal,
    })

    if (!response.ok) throw new ApiError(response.status, await parseError(response))
    if (response.status === 204) return undefined as T
    return (await response.json()) as T
  } finally {
    window.clearTimeout(timeout)
  }
}
