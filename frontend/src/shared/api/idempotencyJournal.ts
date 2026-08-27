import { ApiError } from '@/shared/api/ApiError'

export type IdempotencyJournalFailureResolution =
  | 'retrySameRequest'
  | 'startNewRequest'
  | 'confirmBeforeNewRequest'

type IdempotencyJournalFailurePolicy = Readonly<{
  startNewRequestCodes: ReadonlySet<string>
  confirmBeforeNewRequestCodes: ReadonlySet<string>
}>

export function resolveIdempotencyJournalFailure(
  error: unknown,
  policy: IdempotencyJournalFailurePolicy,
): IdempotencyJournalFailureResolution {
  if (!(error instanceof ApiError)) return 'retrySameRequest'
  if (policy.confirmBeforeNewRequestCodes.has(error.code)) return 'confirmBeforeNewRequest'
  if (policy.startNewRequestCodes.has(error.code)) return 'startNewRequest'
  return 'retrySameRequest'
}
