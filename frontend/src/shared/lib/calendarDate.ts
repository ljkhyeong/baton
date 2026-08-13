const CALENDAR_DATE_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/
const MILLISECONDS_PER_DAY = 24 * 60 * 60 * 1000

function normalizedCalendarDate(value: unknown) {
  if (typeof value !== 'string') return null

  const match = CALENDAR_DATE_PATTERN.exec(value)
  if (!match) return null

  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  const normalized = new Date(0)
  normalized.setUTCHours(0, 0, 0, 0)
  normalized.setUTCFullYear(year, month - 1, day)

  return normalized.getUTCFullYear() === year
    && normalized.getUTCMonth() === month - 1
    && normalized.getUTCDate() === day
    ? normalized
    : null
}

export function isCalendarDate(value: unknown): value is string {
  return normalizedCalendarDate(value) !== null
}

export function calendarDayNumber(value: string) {
  const normalized = normalizedCalendarDate(value)
  if (!normalized) {
    throw new RangeError(`유효하지 않은 달력 날짜입니다: ${value}`)
  }
  return Math.floor(normalized.getTime() / MILLISECONDS_PER_DAY)
}
