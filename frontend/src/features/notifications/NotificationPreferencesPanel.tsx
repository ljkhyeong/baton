import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { getNotificationPreferences, configureNotificationPreferences, type NotificationPreferences } from './api'

type Draft = Pick<NotificationPreferences, 'deadlineSoonEnabled' | 'overdueEnabled' | 'handoffEnabled' | 'deadlineLeadHours'>
export function NotificationPreferencesPanel({ accountId }: { accountId: string }) {
  const client = useQueryClient()
  const key = ['auth', 'notification-preferences', accountId]
  const query = useQuery({ queryKey: key, queryFn: () => getNotificationPreferences(accountId), staleTime: 0 })
  const [draft, setDraft] = useState<Draft | null>(null)
  const values = draft ?? query.data
  const save = useMutation({ mutationFn: () => configureNotificationPreferences(accountId, {
    deadlineSoonEnabled: values!.deadlineSoonEnabled, overdueEnabled: values!.overdueEnabled,
    handoffEnabled: values!.handoffEnabled, deadlineLeadHours: values!.deadlineLeadHours,
    expectedAccountId: accountId, expectedVersion: query.data!.version,
  }), onSuccess: async result => {
    client.setQueryData(key, result); setDraft(null)
    await client.invalidateQueries({ queryKey: ['teams'], predicate: entry => entry.queryKey.includes('notifications') && entry.queryKey.includes(accountId) })
  }, onError: () => { void query.refetch() } })
  if (query.isPending) return <p role="status">알림 설정을 불러오고 있습니다.</p>
  return <section className="notification-preferences" aria-label="개인 알림 설정">
    <p>이 계정의 모든 팀과 기기에 적용합니다. 서비스 내 알림함의 표시를 설정합니다.</p>
    {query.isError && <p role="alert">{query.error.message} <button type="button" onClick={() => void query.refetch()}>다시 불러오기</button></p>}
    {values && <form onSubmit={event => { event.preventDefault(); if (!query.isError && !query.isFetching && !save.isPending) save.mutate() }}>
      <fieldset disabled={query.isError || query.isFetching || save.isPending}>
        <legend>표시할 알림</legend>
        <label><input type="checkbox" checked={values.deadlineSoonEnabled} onChange={event => setDraft({ ...values, deadlineSoonEnabled: event.target.checked })} />마감 임박</label>
        <label className="notification-lead">마감 몇 시간 전부터 표시할까요?<input type="number" min={1} max={168} required value={values.deadlineLeadHours}
          onChange={event => setDraft({ ...values, deadlineLeadHours: Number(event.target.value) })} /></label>
        <label><input type="checkbox" checked={values.overdueEnabled} onChange={event => setDraft({ ...values, overdueEnabled: event.target.checked })} />기한 지남</label>
        <label><input type="checkbox" checked={values.handoffEnabled} onChange={event => setDraft({ ...values, handoffEnabled: event.target.checked })} />인수인계 수락 요청</label>
        <button type="submit">{save.isPending ? '알림 설정 저장 중…' : '알림 설정 저장'}</button>
      </fieldset>
    </form>}
    {save.isError && <p role="alert">{save.error.message} 최신 설정을 확인한 뒤 다시 저장해 주세요.</p>}
    {save.isSuccess && <p role="status">알림 설정을 저장했습니다.</p>}
  </section>
}
