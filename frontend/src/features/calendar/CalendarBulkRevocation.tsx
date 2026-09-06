import { useEffect, useRef, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '@/shared/api/ApiError'
import { isSameUuid } from '@/shared/api/responseValidation'
import { getCalendarSubscription, revokeCalendarSubscription } from './api'
import type { CalendarSubscription, CalendarSubscriptionSummary } from './types'

export const MAX_BULK_REVOCATIONS = 20

type Outcome = 'REVOKED' | 'PENDING' | 'CHECK_REQUIRED' | 'CHANGED' | 'ACCOUNT_REQUIRED' | 'NOT_ATTEMPTED'
type Result = { subscription: CalendarSubscriptionSummary; outcome: Outcome }
const outcomeLabels: Record<Outcome, string> = {
  REVOKED: '해제됨', PENDING: '해제 처리 중', CHECK_REQUIRED: '해제 여부 확인 필요', CHANGED: '구독 변경됨 · 다시 확인',
  ACCOUNT_REQUIRED: '로그인 계정 확인 필요', NOT_ATTEMPTED: '요청하지 않음',
}
const accountError = (error: unknown) => error instanceof ApiError && [401, 403].includes(error.status)
function observedOutcome(status: CalendarSubscription, expectedId: string): Outcome {
  if (status.status === 'NOT_CREATED') return 'REVOKED'
  if (!isSameUuid(status.subscriptionId, expectedId)) return 'CHANGED'
  if (status.status === 'REVOKED') return 'REVOKED'
  return status.status === 'REVOCATION_PENDING' ? 'PENDING' : 'CHECK_REQUIRED'
}

type Props = {
  accountId: string; enabled: boolean; selected: CalendarSubscriptionSummary[]; disabled: boolean
  onLockChange: (locked: boolean) => void; onFinished: () => void
}
export default function CalendarBulkRevocation({ accountId, enabled, selected, disabled, onLockChange, onFinished }: Props) {
  const cache = useQueryClient()
  const [confirmation, setConfirmation] = useState<CalendarSubscriptionSummary[] | null>(null)
  const [results, setResults] = useState<Result[]>([])
  const [total, setTotal] = useState(0)
  const controller = useRef<AbortController | null>(null)
  const locked = useRef(false)
  useEffect(() => () => controller.current?.abort(), [])
  const operation = useMutation({
    mutationFn: async (targets: CalendarSubscriptionSummary[]) => {
      const abort = new AbortController()
      controller.current = abort
      const completed: Result[] = []
      let stopped = false
      for (const target of targets) {
        if (abort.signal.aborted || stopped) {
          completed.push({ subscription: target, outcome: 'NOT_ATTEMPTED' })
          continue
        }
        const scope = { accountId, teamId: target.teamId, seasonId: target.seasonId, accessKey: '' }
        let requested = false
        let outcome: Outcome
        try {
          const status = await getCalendarSubscription(scope, abort.signal)
          outcome = observedOutcome(status, target.subscriptionId)
          if (outcome !== 'REVOKED' && outcome !== 'CHANGED') {
            requested = true
            await revokeCalendarSubscription(scope, abort.signal)
            outcome = 'REVOKED'
          }
        } catch (error) {
          if (abort.signal.aborted) outcome = requested ? 'CHECK_REQUIRED' : 'NOT_ATTEMPTED'
          else if (accountError(error)) { outcome = 'ACCOUNT_REQUIRED'; stopped = true }
          else {
            outcome = 'CHECK_REQUIRED'
            if (requested) {
              try { outcome = observedOutcome(await getCalendarSubscription(scope, abort.signal), target.subscriptionId) }
              catch (checkError) {
                if (accountError(checkError)) { outcome = 'ACCOUNT_REQUIRED'; stopped = true }
              }
            }
          }
        }
        completed.push({ subscription: target, outcome })
        setResults([...completed])
      }
      setResults(completed)
      // 결과를 알 수 없는 해제 요청은 반복하지 않고 사용자가 상태를 확인하도록 남긴다.
    },
    retry: false, gcTime: 0, networkMode: 'always',
    onSettled: () => {
      locked.current = false
      onLockChange(false)
      onFinished()
      void cache.invalidateQueries({ queryKey: ['calendar-subscriptions', accountId] })
      void cache.invalidateQueries({ queryKey: ['calendar-subscription', accountId], refetchType: 'none' })
    },
  })
  function start() {
    if (locked.current || !confirmation) return
    locked.current = true
    const targets = confirmation
    setConfirmation(null)
    setResults([])
    setTotal(targets.length)
    operation.mutate(targets)
  }
  return <div className="calendar-bulk">
    {enabled && !confirmation && !operation.isPending && <button type="button" className="primary-button"
      disabled={disabled || selected.length === 0} onClick={() => { setConfirmation([...selected]); onLockChange(true) }}>
      선택한 구독 {selected.length}개 해제
    </button>}
    {confirmation && <div className="calendar-confirm" role="group" aria-label="선택한 구독 해제 확인">
      <strong>선택한 구독 {confirmation.length}개를 해제할까요?</strong>
      <ul>{confirmation.map(row => <li key={row.subscriptionId}>{row.teamName} · {row.seasonName}</li>)}</ul>
      <p>기존 구독 주소로 일정을 가져올 수 없게 됩니다. 캘린더 앱에 이미 저장된 일정은 앱에서 직접 제거해 주세요.</p>
      <button type="button" className="primary-button" onClick={start}>{confirmation.length}개 구독 해제</button>
      <button type="button" className="secondary-button" onClick={() => { setConfirmation(null); onLockChange(false) }}>취소</button>
    </div>}
    {operation.isPending && <div className="calendar-actions">
      <p role="status">구독 해제 중 · 결과 확인 {results.length}/{total}개</p>
      <button type="button" className="secondary-button" onClick={() => controller.current?.abort()}>남은 구독 해제 중단</button>
    </div>}
    {results.length > 0 && <section className="calendar-bulk-results" aria-label="구독 해제 결과">
      <p role="status">해제됨 {results.filter(result => result.outcome === 'REVOKED').length}개 · 처리 중 {results.filter(result => result.outcome === 'PENDING').length}개 · 확인 필요 {results.filter(result => !['REVOKED', 'PENDING'].includes(result.outcome)).length}개</p>
      <ul>{results.map(result => <li key={result.subscription.subscriptionId}>
        <span>{result.subscription.teamName} · {result.subscription.seasonName}</span><strong>{outcomeLabels[result.outcome]}</strong>
      </li>)}</ul>
      {!operation.isPending && <>
        {results.some(result => result.outcome !== 'REVOKED') && <p>‘선택 마치기’를 누른 뒤, 해제 여부가 확인되지 않은 구독을 열어 확인하세요. 중단 전에 보낸 해제 요청은 나중에 완료될 수 있습니다.</p>}
        <button type="button" className="secondary-button" onClick={() => setResults([])}>결과 닫기</button>
      </>}
    </section>}
  </div>
}
