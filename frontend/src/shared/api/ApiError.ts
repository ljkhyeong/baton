import type { ErrorResponse } from '@/shared/types/ErrorResponse'

export type ApiClientErrorKind = 'network' | 'timeout' | 'invalid-response'

const apiClientErrorMessages = {
  network: '서버에 연결하지 못해 요청 결과를 확인할 수 없습니다. 네트워크 연결을 확인한 뒤 다시 시도해 주세요.',
  timeout: '서버 응답이 늦어 요청 결과를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.',
  'invalid-response': '서버 응답을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.',
} satisfies Record<ApiClientErrorKind, string>

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly requestId?: string

  constructor(status: number, response: ErrorResponse, requestId?: string | null) {
    const normalizedRequestId = requestId?.trim() || undefined
    const message = status >= 500 && normalizedRequestId
      ? `${response.message} (요청 ID: ${normalizedRequestId})`
      : response.message
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = response.code
    this.requestId = normalizedRequestId
  }
}

export class ApiClientError extends Error {
  readonly kind: ApiClientErrorKind

  constructor(kind: ApiClientErrorKind, cause: unknown) {
    super(apiClientErrorMessages[kind], { cause })
    this.name = 'ApiClientError'
    this.kind = kind
  }
}
