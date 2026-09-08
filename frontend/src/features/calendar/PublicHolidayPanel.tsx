import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import type { operations } from '@/generated/api'
import { apiRequest } from '@/shared/api/client'
import { isInstant, isJsonObject, isNonEmptyString } from '@/shared/api/responseValidation'
import { isCalendarDate } from '@/shared/lib/calendarDate'
import { formatLocalDate } from '@/features/workspace/workspacePresentation'
import './calendar.scss'

type HolidayCalendar = operations['getPublicHolidays']['responses']['200']['content']['application/json']

function decodeCalendar(value: unknown, year: number): HolidayCalendar {
  if (!isJsonObject(value) || value.year !== year
    || !['READY', 'UNAVAILABLE', 'DISABLED', 'OUT_OF_RANGE'].includes(String(value.status))
    || (value.checkedAt !== null && !isInstant(value.checkedAt))
    || !Array.isArray(value.holidays)
    || !value.holidays.every(item => isJsonObject(item)
      && typeof item.date === 'string' && isCalendarDate(item.date)
      && Number(item.date.slice(0, 4)) === year && isNonEmptyString(item.name))) {
    throw new Error('공휴일 응답을 확인할 수 없습니다.')
  }
  return value as HolidayCalendar
}

export function PublicHolidayPanel({ date, meetingDate }: { date: string; meetingDate?: string }) {
  const [open, setOpen] = useState(false)
  const [chosenMonth, setChosenMonth] = useState('')
  const month = chosenMonth || date.slice(0, 7)
  const year = Number(month.slice(0, 4))
  const query = useQuery({
    queryKey: ['public-holidays', 'KR', year],
    queryFn: ({ signal }) => apiRequest('/api/v1/calendar/holidays', {
      query: { year }, signal, decode: value => decodeCalendar(value, year),
    }),
    enabled: open,
    staleTime: 60 * 60 * 1000,
    retry: false,
  })
  const holidays = query.data?.holidays.filter(item => item.date.startsWith(month)) ?? []
  const meetingHolidays = query.data?.holidays.filter(item => item.date === meetingDate) ?? []
  return <details className="calendar-panel public-holiday-panel" onToggle={event => setOpen(event.currentTarget.open)}>
    <summary>대한민국 공휴일 확인</summary>
    {open && <div className="calendar-content">
      <label className="holiday-month">조회할 달
        <input type="month" value={month} onChange={event => setChosenMonth(event.target.value)} />
      </label>
      {query.isPending && <p role="status">공휴일을 불러오는 중입니다.</p>}
      {query.isError && <p role="alert">공휴일을 불러오지 못했습니다. <button type="button" onClick={() => void query.refetch()}>다시 확인</button></p>}
      {query.data?.status === 'DISABLED' && <p>현재 공휴일 조회를 사용할 수 없습니다.</p>}
      {query.data?.status === 'UNAVAILABLE' && <p role="status">공휴일 정보를 확인하지 못했습니다. 잠시 후 다시 확인해 주세요.</p>}
      {query.data?.status === 'OUT_OF_RANGE' && <p>공휴일은 작년부터 내년까지 조회할 수 있습니다.</p>}
      {query.data?.status === 'READY' && <>
        {meetingHolidays.length > 0 && <p className="holiday-notice">모임일 {formatLocalDate(date)}은 {meetingHolidays.map(item => item.name).join(' · ')}입니다.</p>}
        {holidays.length > 0 ? <ul className="holiday-list">
          {holidays.map(item => <li key={`${item.date}-${item.name}`}><time dateTime={item.date}>{formatLocalDate(item.date)}</time><strong>{item.name}</strong></li>)}
        </ul> : <p>이 달에는 등록된 공휴일이 없습니다.</p>}
        <p>공휴일에도 회차는 예정대로 생성됩니다. 쉬는 회차는 직접 건너뛰어 주세요.</p>
        <small>자료: <a href="https://www.data.go.kr/data/15012690/openapi.do" target="_blank" rel="noreferrer">한국천문연구원</a>
          {query.data.checkedAt && ` · 확인 ${new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(new Date(query.data.checkedAt))} (한국 시각)`}
        </small>
      </>}
    </div>}
  </details>
}
