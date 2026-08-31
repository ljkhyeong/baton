import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '@/shared/api/ApiError'
import { generateEdition, getLatestEdition } from './api'
import { attentionReasons } from './types'
import type { AttentionItem, BriefScope } from './types'

export function BriefEditionSection(props: { scope: BriefScope; readOnly: boolean }) {
  const [open, setOpen] = useState(false)
  return <details className="brief-edition" onToggle={(event) => setOpen(event.currentTarget.open)}>
    <summary>저장된 브리프</summary>
    {open && <BriefEditionResults {...props} />}
  </details>
}

function BriefEditionResults({ scope, readOnly }: { scope: BriefScope; readOnly: boolean }) {
  const queryClient = useQueryClient()
  const key = ['brief', scope.accountId, scope.teamId, scope.seasonId, { accessKey: scope.accessKey }, 'latest-edition']
  const latest = useQuery({ queryKey: key, queryFn: ({ signal }) => getLatestEdition(scope, signal), retry: false, staleTime: 0 })
  const generation = useMutation({ mutationFn: () => generateEdition(scope), retry: false,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: key }) })
  const accessError = [latest.error, generation.error].find((error) => error instanceof ApiError && (error.status === 401 || error.status === 403))
  if (accessError) return <p role="alert">{accessError.message}{' '}
    <button type="button" disabled={latest.isFetching || generation.isPending}
      onClick={async () => { const result = await latest.refetch(); if (!result.isError) generation.reset() }}>브리프 조회 권한 다시 확인</button></p>
  const edition = latest.isError ? undefined : latest.data
  const time = edition ? new Intl.DateTimeFormat('ko-KR', { timeZone: edition.zoneId, dateStyle: 'short', timeStyle: 'short' }) : null
  const missing = latest.error instanceof ApiError && latest.error.code === 'BRIEF_EDITION_NOT_FOUND'
  return <section aria-label="최신 불변 브리프">
    <p>생성 당시 내용을 고정한 브리프입니다. 현재 관심 항목이 바뀌어도 저장된 내용은 바뀌지 않습니다.</p>
    <div className="brief-pagination">
      <button type="button" disabled={latest.isFetching || generation.isPending} onClick={() => void latest.refetch()}>최신 브리프 조회</button>
      <button type="button" disabled={readOnly || generation.isPending || latest.isFetching} onClick={() => generation.mutate()}>
        {generation.isPending ? '이번 주 브리프 생성 중…' : '이번 주 브리프 생성'}
      </button>
    </div>
    {readOnly ? <p>종료된 시즌은 저장된 브리프만 조회할 수 있습니다.</p>
      : <p className="brief-note">서버가 시즌 시간대의 이번 주와 전달 완료 범위를 정합니다. 기존 브리프를 덮어쓰지 않으며 같은 상태는 재사용합니다.</p>}
    {generation.isError && <p role="alert">{generation.error.message} 자동 재시도하지 않습니다. 최신 브리프를 확인한 뒤 필요하면 생성 버튼으로 다시 요청해 주세요.</p>}
    {generation.isSuccess && <p role="status">{generation.data.created ? '새 브리프를 생성했습니다.' : '기존 브리프를 재사용했습니다.'}
      {' '}세대 {generation.data.generation}</p>}
    {latest.isPending && <p role="status">저장된 브리프를 불러오고 있습니다.</p>}
    {missing && <p role="status">아직 저장된 브리프가 없습니다.</p>}
    {latest.isError && !missing && <p role="alert">저장된 브리프를 불러오지 못했습니다. {latest.error.message}</p>}
    {edition && time && <>
      <h3>{edition.weekStart} 시작 주 · 세대 {edition.generation}</h3>
      <p>생성 {time.format(new Date(edition.generatedAt))} ({edition.zoneId})</p>
      <p className="brief-note">집계 구간: {time.format(new Date(edition.windowStart))} 이상 ~ {time.format(new Date(edition.windowEnd))} 미만
        {' '}· 수신 커서 {edition.sourceCursor} · 규칙 {edition.ruleVersion}</p>
      {edition.items.length === 0 ? <p>이 브리프에 선정된 관심 항목이 없습니다.</p>
        : <ul className="brief-items">{edition.items.map((item) => <li key={`${item.reasonCode}:${item.sourceReference}`}>
          <div><strong>{attentionReasons[item.reasonCode as AttentionItem['reasonCode']]}</strong>
            <span>{item.severity === 'HIGH' ? '높음' : '보통'} · {item.status === 'ACTIVE' ? '활성' : '해소'}</span></div>
          <small>원본 참조 <code>{item.sourceReference}</code></small>
          <small>관측 {time.format(new Date(item.observedAt))} ({edition.zoneId})</small>
          <small>{item.aggregateRevision === null ? '이전 브리프: 리비전·공백 근거 미기록'
            : `리비전 ${item.aggregateRevision} · ${item.revisionGap ? '누적 공백 기록 있음' : '누적 공백 기록 없음'}`}</small>
        </li>)}</ul>}
    </>}
  </section>
}
