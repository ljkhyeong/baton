// 화면의 시각 표기를 `2026. 7. 18. 오후 9:00`, 날짜 표기를 `2026. 7. 18.`, 하루 중 시각을 `오후 9:00`로 통일한다.
export function formatInstant(value: string | number, timeZone?: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
    ...(timeZone ? { timeZone } : {}),
  }).format(new Date(value))
}

export function formatInstantDate(value: string | number, timeZone?: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    ...(timeZone ? { timeZone } : {}),
  }).format(new Date(value))
}

// `HH:mm` 형식의 시각은 시간대 변환 없이 표시한다.
export function formatLocalTime(value: string) {
  const [hour, minute] = value.split(':').map(Number)
  return new Intl.DateTimeFormat('ko-KR', { timeStyle: 'short', timeZone: 'UTC' })
    .format(Date.UTC(2000, 0, 1, hour, minute))
}
