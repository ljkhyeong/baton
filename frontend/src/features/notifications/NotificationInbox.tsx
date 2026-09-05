import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { NotificationPreferencesPanel } from './NotificationPreferencesPanel'
import type { WorkspaceProjection } from '@/features/workspace/types'
import { getNotifications, readNotification, type NotificationScope } from './api'
import './notifications.scss'

export function NotificationInbox({ scope, workspace, onOpenRound, onOpenHandoff }: {
  scope: NotificationScope; workspace: WorkspaceProjection
  onOpenRound: (roundId: string, executionId: string) => void
  onOpenHandoff: (roleId: string) => void
}) {
  const [settingsOpen, setSettingsOpen] = useState(false)
  const client = useQueryClient()
  const key = ['teams', scope.teamId, 'seasons', scope.seasonId, 'notifications', scope.accountId, scope.accessKey]
  const inbox = useQuery({ queryKey: key, queryFn: () => getNotifications(scope),
    refetchInterval: 30_000, refetchIntervalInBackground: false })
  useEffect(() => {
    void client.invalidateQueries({ queryKey: ['teams', scope.teamId, 'seasons', scope.seasonId, 'notifications', scope.accountId] })
  }, [client, scope.teamId, scope.seasonId, scope.accountId, workspace])
  const read = useMutation({ mutationFn: (id: string) => readNotification(scope, id),
    onSuccess: data => client.setQueryData(key, data),
    onError: () => { void inbox.refetch() } })
  const notifications = inbox.data?.notifications ?? []
  const unread = notifications.filter(item => !item.read).length
  const date = new Intl.DateTimeFormat('ko-KR', { timeZone: workspace.season.timeZone,
    month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false })
  return <details className="notification-inbox">
    <summary>내 알림 {inbox.data ? `· 안 읽음 ${unread}건` : ''}</summary>
    <p>내 설정에 맞는 마감·지연 업무와 수락할 인수인계입니다. 완료된 업무는 목록에서 빠집니다.</p>
    <button type="button" aria-expanded={settingsOpen} onClick={() => setSettingsOpen(!settingsOpen)}>알림 설정</button>
    {settingsOpen && <NotificationPreferencesPanel key={scope.accountId} accountId={scope.accountId} />}
    {inbox.isPending ? <p role="status">알림을 불러오고 있습니다.</p>
      : inbox.isError ? <p role="alert">{inbox.error.message} <button type="button" onClick={() => void inbox.refetch()}>다시 불러오기</button></p>
        : notifications.length === 0 ? <p>지금 확인할 알림이 없습니다.</p>
          : <ul>{notifications.map(item => <li key={item.id} data-read={item.read}>
            <button type="button" className="notification-source" onClick={() => {
              if (item.kind === 'HANDOFF_REQUEST') onOpenHandoff(item.roleId)
              else if (item.roundId) onOpenRound(item.roundId, item.sourceId)
            }}>
              <span>{item.kind === 'HANDOFF_REQUEST' ? '인수인계 수락 요청' : item.kind === 'OVERDUE' ? '기한 지남' : '마감 임박'} · {item.read ? '읽음' : '안 읽음'}</span>
              <strong>{item.title}</strong>
              <small>{date.format(new Date(item.occurredAt))}{item.kind === 'HANDOFF_REQUEST' ? ' 전달' : ' 마감'} · 원본 보기</small>
            </button>
            {!item.read && <button type="button" disabled={read.isPending || inbox.isError}
              aria-label={`${item.title} 알림 읽음 처리`} onClick={() => read.mutate(item.id)}>읽음 처리</button>}
          </li>)}</ul>}
    {read.isError && <p role="alert">{read.error.message}</p>}
  </details>
}
