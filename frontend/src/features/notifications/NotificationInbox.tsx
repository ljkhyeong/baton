import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { isAccessDenied } from '@/shared/api/ApiError'
import { formatInstant } from '@/shared/lib/dateTimeFormat'
import { NotificationPreferencesPanel } from './NotificationPreferencesPanel'
import type { WorkspaceProjection } from '@/features/workspace/types'
import { getNotifications, readNotification, type NotificationScope } from './api'
import './notifications.scss'


export function NotificationInbox({ scope, workspace, onOpenRound, onOpenHandoff }: {
  scope: NotificationScope; workspace: WorkspaceProjection
  onOpenRound: (roundId: string, executionId: string) => void
  onOpenHandoff: (roleId: string) => void
}) {
  const [open, setOpen] = useState(false)
  const [unreadOnly, setUnreadOnly] = useState(false)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const client = useQueryClient()
  const key = ['teams', scope.teamId, 'seasons', scope.seasonId, 'notifications', scope.accountId, scope.accessKey]
  const inbox = useQuery({ queryKey: key, queryFn: ({ signal }) => getNotifications(scope, signal),
    enabled: query => open && !isAccessDenied(query.state.error),
    retry: (count, error) => !isAccessDenied(error) && count < 1,
    refetchInterval: 30_000 })
  useEffect(() => {
    void client.invalidateQueries({ queryKey: ['teams', scope.teamId, 'seasons', scope.seasonId, 'notifications', scope.accountId] })
  }, [client, scope.teamId, scope.seasonId, scope.accountId, workspace])
  const read = useMutation({ mutationFn: (id: string) => readNotification(scope, id),
    onSuccess: data => client.setQueryData(key, data),
    onError: () => { void client.invalidateQueries({ queryKey: key, exact: true }) } })
  const notifications = inbox.data?.notifications ?? []
  const unread = notifications.filter(item => !item.read).length
  const visible = unreadOnly ? notifications.filter(item => !item.read) : notifications
  return <details className="notification-inbox" open={open} onToggle={event => {
    const expanded = event.currentTarget.open
    setOpen(expanded)
    if (!expanded) void client.cancelQueries({ queryKey: key, exact: true })
  }}>
    <summary>내 알림 {inbox.data && !inbox.isError ? `· 안 읽음 ${unread}건` : ''}</summary>
    {open && <>
      <p>마감이 다가오거나 지난 업무, 내가 수락할 인수인계를 알려드립니다. 완료한 업무는 표시하지 않습니다.</p>
      <div className="notification-controls">
        <label><input type="checkbox" checked={unreadOnly} onChange={event => setUnreadOnly(event.target.checked)} />안 읽은 알림만</label>
        <button type="button" className="text-button" aria-expanded={settingsOpen} onClick={() => setSettingsOpen(!settingsOpen)}>알림 설정</button>
      </div>
      {settingsOpen && <NotificationPreferencesPanel key={scope.accountId} accountId={scope.accountId} />}
      {inbox.isPending ? <p role="status">알림을 불러오고 있습니다.</p>
        : inbox.isError ? <p role="alert">{inbox.error.message} <button className="secondary-button" type="button" onClick={() => void inbox.refetch()}>다시 불러오기</button></p>
          : visible.length === 0 ? <p>{unreadOnly && notifications.length > 0 ? '안 읽은 알림이 없습니다.' : '지금 확인할 알림이 없습니다.'}</p>
            : <ul>{visible.map(item => <li key={item.id} data-read={item.read}>
              <button type="button" className="notification-source" onClick={() => {
                if (item.kind === 'HANDOFF_REQUEST') onOpenHandoff(item.roleId)
                else if (item.roundId) onOpenRound(item.roundId, item.sourceId)
              }}>
                <span>{item.kind === 'HANDOFF_REQUEST' ? '인수인계 수락 요청' : item.kind === 'OVERDUE' ? '기한 지남' : '마감 임박'} · {item.read ? '읽음' : '안 읽음'}</span>
                <strong>{item.title}</strong>
                <small>{formatInstant(item.occurredAt, workspace.season.timeZone)}{item.kind === 'HANDOFF_REQUEST' ? ' 전달' : ' 마감'} · 관련 항목 보기</small>
              </button>
              {!item.read && <button type="button" className="text-button" disabled={read.isPending || inbox.isError}
                aria-label={`${item.title} 알림 읽음 처리`} onClick={() => read.mutate(item.id)}>읽음 처리</button>}
            </li>)}</ul>}
      {read.isError && <p role="alert">{read.error.message}</p>}
      {read.isSuccess && <p role="status">알림을 읽음으로 표시했습니다.</p>}
    </>}
  </details>
}
