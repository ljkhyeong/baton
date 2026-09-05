import { createContext, useContext } from 'react'
import type { ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ApiError } from '@/shared/api/ApiError'
import { queryBriefSources } from './api'
import type { AttentionCursor, BriefScope, BriefSource } from './types'
import { attentionReasons } from './types'

type Item = { reasonCode: string; sourceReference: string }
export const BriefSourceContext = createContext<{ sources: BriefSource[]; loading: boolean; onOpen: (source: BriefSource) => void }>({ sources: [], loading: false, onOpen: () => {} })

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
  return <BriefSourceContext value={{ sources: query.isError ? [] : query.data?.sources ?? [], loading: query.isFetching, onOpen }}>
    {query.isError && <p role="alert">현재 업무 정보를 불러오지 못했습니다. <button type="button" onClick={() => void query.refetch()}>업무 정보 다시 조회</button></p>}
    {children}
  </BriefSourceContext>
}

const sourceActions: Partial<Record<AttentionCursor['eventType'], { label: string; description: string }>> = {
  ROLE_UNASSIGNED: { label: '담당자 확인', description: '현재 담당자와 담당 기간을 확인해 주세요.' },
  ROLE_SUCCESSOR_MISSING: { label: '다음 담당자 확인', description: '다음 담당자와 역할을 넘겨줄 기간을 확인해 주세요.' },
  ROLE_PREPARATION_INCOMPLETE: { label: '역할 준비 확인', description: '역할의 목적·책임과 필요한 자료를 확인해 주세요.' },
  ROUTINE_REPEATEDLY_OVERDUE: { label: '지연된 루틴 확인', description: '루틴의 마감과 회차별 진행 상태를 확인해 주세요.' },
  HANDOFF_INCOMPLETE: { label: '인수인계 확인', description: '남은 인수인계 항목과 전달·수락 상태를 확인해 주세요.' },
}

export function BriefSourceLink({ item, readOnly }: { item: Item & { status: string }; readOnly: boolean }) {
  const { sources, loading, onOpen } = useContext(BriefSourceContext)
  const source = sources.find((entry) => entry.eventType === item.reasonCode && entry.sourceReference === item.sourceReference)
  const action = sourceActions[item.reasonCode as AttentionCursor['eventType']]
  const label = source?.target?.archived ? '보관된 루틴 보기'
    : readOnly || item.status === 'RESOLVED' ? '현재 업무 보기' : action?.label ?? '현재 업무 보기'
  const description = readOnly ? '종료된 시즌의 업무 기록을 확인할 수 있습니다.'
    : source?.target?.archived ? '보관된 루틴의 기록을 확인할 수 있습니다.'
    : item.status === 'RESOLVED' ? '해소 이후의 현재 업무 상태를 확인할 수 있습니다.' : action?.description
  return source?.target ? <div className="brief-source">
    <span>현재 업무: <strong>{source.target.title}</strong>{source.target.archived && ' · 보관됨'}</span>
    <button type="button" onClick={() => onOpen(source)}>{label}</button>
    {description && <small className="brief-action-description">{description}</small>}
  </div> : <small>{loading ? '현재 업무 확인 중…' : '현재 업무를 연결할 수 없습니다.'}</small>
}
