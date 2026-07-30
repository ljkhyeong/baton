import type { Season } from './types'

const MILLISECONDS_PER_DAY = 24 * 60 * 60 * 1000
const DAYS_PER_WEEK = 7
const CALENDAR_DATE_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/

export const PILOT_CALENDAR_TIME_ZONE = 'Asia/Seoul'

function calendarDayNumber(value: string) {
  const match = CALENDAR_DATE_PATTERN.exec(value)
  if (!match) throw new RangeError(`유효하지 않은 달력 날짜입니다: ${value}`)

  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  const normalized = new Date(0)
  normalized.setUTCHours(0, 0, 0, 0)
  normalized.setUTCFullYear(year, month - 1, day)

  if (
    normalized.getUTCFullYear() !== year
    || normalized.getUTCMonth() !== month - 1
    || normalized.getUTCDate() !== day
  ) {
    throw new RangeError(`유효하지 않은 달력 날짜입니다: ${value}`)
  }

  return Math.floor(normalized.getTime() / MILLISECONDS_PER_DAY)
}

export function pilotCalendarDate(now = new Date(), timeZone = PILOT_CALENDAR_TIME_ZONE) {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone,
    calendar: 'gregory',
    numberingSystem: 'latn',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(now)
  const valueByPart = new Map(parts.map((part) => [part.type, part.value]))
  const year = valueByPart.get('year')
  const month = valueByPart.get('month')
  const day = valueByPart.get('day')
  if (!year || !month || !day) {
    throw new Error('파일럿 달력 날짜를 계산할 수 없습니다.')
  }
  return `${year}-${month}-${day}`
}

export function formatPilotToday(now = new Date(), timeZone = PILOT_CALENDAR_TIME_ZONE) {
  return new Intl.DateTimeFormat('ko-KR', {
    timeZone,
    calendar: 'gregory',
    month: 'long',
    day: 'numeric',
    weekday: 'long',
  }).format(now)
}

export function seasonProgress(
  season: Pick<Season, 'startDate' | 'endDate'>,
  today: string,
) {
  const startDay = calendarDayNumber(season.startDate)
  const endDay = calendarDayNumber(season.endDate)
  const todayDay = calendarDayNumber(today)
  if (endDay < startDay) {
    throw new RangeError('시즌 종료일은 시작일보다 빠를 수 없습니다.')
  }
  const durationDays = endDay - startDay

  if (durationDays === 0) {
    const completed = todayDay >= endDay
    return {
      percent: completed ? 100 : 0,
      totalWeeks: 1,
      elapsedWeeks: completed ? 1 : 0,
    }
  }

  const elapsedDays = Math.min(durationDays, Math.max(0, todayDay - startDay))
  const totalWeeks = Math.max(1, Math.ceil(durationDays / DAYS_PER_WEEK))
  return {
    percent: Math.round((elapsedDays / durationDays) * 100),
    totalWeeks,
    elapsedWeeks: Math.min(totalWeeks, Math.ceil(elapsedDays / DAYS_PER_WEEK)),
  }
}

export function daysUntil(value: string, today: string) {
  return calendarDayNumber(value) - calendarDayNumber(today)
}
