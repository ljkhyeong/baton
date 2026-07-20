import type { ErrorResponse } from '@/shared/types/ErrorResponse'

export class ApiError extends Error {
  readonly status: number
  readonly code: string

  constructor(status: number, response: ErrorResponse) {
    super(response.message)
    this.name = 'ApiError'
    this.status = status
    this.code = response.code
  }
}
