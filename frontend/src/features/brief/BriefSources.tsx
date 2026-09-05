import { createContext, useContext } from 'react'
import type { ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ApiError } from '@/shared/api/ApiError'
import { queryBriefSources } from './api'
import type { AttentionCursor, BriefScope, BriefSource } from './types'
import { attentionReasons } from './types'

type Item = { reasonCode: string; sourceReference: string }
const Sources = createContext<{ sources: BriefSource[]; loading: boolean; onOpen: (source: BriefSource) => void }>({ sources: [], loading: false, onOpen: () => {} })

export function BriefSources({ scope, items, onOpen, children }: {
  scope: BriefScope; items: Item[]; onOpen: (source: BriefSource) => void; children: ReactNode
}) {
  const identities = [...new Map(items.filter((item) => Object.hasOwn(attentionReasons, item.reasonCode) && item.sourceReference.length <= 512 && item.sourceReference.startsWith('baton-continuity:'))
    .map((item) => [JSON.stringify([item.reasonCode, item.sourceReference]), { eventType: item.reasonCode as AttentionCursor['eventType'], sourceReference: item.sourceReference }])).values()]
  const query = useQuery({ queryKey: ['brief', scope.accountId, scope.teamId, scope.seasonId, { accessKey: scope.accessKey }, 'sources', identities],
    queryFn: ({ signal }) => queryBriefSources(scope, identities, signal), enabled: identities.length > 0, retry: false, staleTime: 0 })
  const error = query.error
  if (error instanceof ApiError && (error.status === 401 || error.status === 403)) return <p role="alert">{error.message}{' '}
    <button type="button" onClick={() => void query.refetch()}>업무 조회 권한 다시 확인</button></p>
  return <Sources value={{ sources: query.isError ? [] : query.data?.sources ?? [], loading: query.isFetching, onOpen }}>
    {query.isError && <p role="alert">현재 업무 정보를 불러오지 못했습니다. <button type="button" onClick={() => void query.refetch()}>업무 정보 다시 조회</button></p>}
    {children}
  </Sources>
}

export function BriefSourceLink({ item }: { item: Item }) {
  const { sources, loading, onOpen } = useContext(Sources)
  const source = sources.find((entry) => entry.eventType === item.reasonCode && entry.sourceReference === item.sourceReference)
  return source?.target ? <div className="brief-source">
    <span>현재 업무: <strong>{source.target.title}</strong>{source.target.archived && ' · 보관됨'}</span>
    <button type="button" onClick={() => onOpen(source)}>업무로 이동</button>
  </div> : <small>{loading ? '현재 업무 확인 중…' : '현재 업무를 연결할 수 없습니다.'}</small>
}
