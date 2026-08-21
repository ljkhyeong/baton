import { isCalendarDate } from '@/shared/lib/calendarDate'

export const UUID_PATTERN_SOURCE =
  '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'
export const ROUND_ROOM_ID_PATTERN_SOURCE =
  '[abcdefghjkmnpqrstuvwxyz23456789]{4}(?:-[abcdefghjkmnpqrstuvwxyz23456789]{4}){2}'

const UUID_PATTERN = new RegExp(`^${UUID_PATTERN_SOURCE}$`, 'i')
const ROUND_ROOM_ID_PATTERN = new RegExp(`^${ROUND_ROOM_ID_PATTERN_SOURCE}$`)
const UTC_INSTANT_PATTERN = /^(\d{4}-\d{2}-\d{2})T(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d{1,9})?Z$/

export function isJsonObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

export function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0
}

export function isUuid(value: unknown): value is string {
  return typeof value === 'string' && UUID_PATTERN.test(value)
}

export function isSameUuid(left: unknown, right: unknown) {
  return isUuid(left)
    && isUuid(right)
    && left.toLowerCase() === right.toLowerCase()
}

export function isRoundRoomId(value: unknown): value is string {
  return typeof value === 'string' && ROUND_ROOM_ID_PATTERN.test(value)
}

export function isInstant(value: unknown): value is string {
  if (typeof value !== 'string') return false

  const match = UTC_INSTANT_PATTERN.exec(value)
  return match !== null && isCalendarDate(match[1])
}

export function isNullableInstant(value: unknown): value is string | null {
  return value === null || isInstant(value)
}
