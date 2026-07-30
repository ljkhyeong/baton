import { ApiError } from '@/shared/api/ApiError'

export function identityErrorMessage(error: unknown) {
  if (error instanceof Error) return error.message
  if (error instanceof ApiError) return error.message
  return '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

export function csrfCredential(
  session: {
    csrfHeaderName: string | null
    csrfToken: string | null
  } | null | undefined,
) {
  if (!session?.csrfHeaderName || !session.csrfToken) return null
  return {
    headerName: session.csrfHeaderName,
    token: session.csrfToken,
  }
}

export function formatIdentityInstant(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('ko-KR', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

