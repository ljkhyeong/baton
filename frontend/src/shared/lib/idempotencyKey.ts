const IDEMPOTENCY_KEY_PATTERN = /^[A-Za-z0-9._~-]{32,200}$/

export function isValidIdempotencyKey(value: unknown): value is string {
  return typeof value === 'string' && IDEMPOTENCY_KEY_PATTERN.test(value)
}
