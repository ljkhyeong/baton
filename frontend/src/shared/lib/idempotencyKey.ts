const IDEMPOTENCY_KEY_PATTERN = /^[A-Za-z0-9._~-]{32,200}$/

export function isValidIdempotencyKey(value: unknown): value is string {
  return typeof value === 'string' && IDEMPOTENCY_KEY_PATTERN.test(value)
}

export function generateIdempotencyKey() {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  const bytes = crypto.getRandomValues(new Uint8Array(32))
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
}
