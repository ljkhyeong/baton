import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '@/shared/api/ApiError'
import { compareEditions, generateEdition, getEdition, getEditionDeliveryStatus, getEditionHistory, getGenerationReadiness, getLatestEdition, getPreviousWeekEdition } from './api'
import { attentionReasons, editionSections } from './types'
import { BriefEditionActions } from './BriefEditionActions'
import type { BriefNavigation } from './useBriefNavigation'
import type { AttentionItem, BriefEdition, BriefDeliveryStatus, BriefReadiness, BriefScope, BriefSource } from './types'
import { BriefSources, BriefSourceLink } from './BriefSources'

type Props = { navigation: BriefNavigation; workspaceName: string; scope: BriefScope; timeZone: string; readOnly: boolean; onGenerated: () => void; onOpenSource: (source: BriefSource) => void }
const sectionNames = { CURRENT_WEEK: '이번 주 변경', CARRY_OVER: '이전 주부터 미해결' }
const deliveryStatusText: Record<BriefDeliveryStatus['status'], string> = {
  ADDITIONAL_DELIVERIES: '마지막 생성 확인 이후 새 변경이 전달됐습니다.',
  NO_ADDITIONAL_DELIVERIES: '마지막 생성 확인 이후 추가 전달 기록이 없습니다.',
  UNKNOWN: '이 브리프의 생성 확인 기록이 없어 추가 전달 여부를 알 수 없습니다.',
}
const readinessText: Record<BriefReadiness['status'], string> = {
  READY: '브리프 생성 가능', DELIVERY_PENDING: 'BRIEF로 변경사항 전송 중', DELIVERY_FAILED: '변경사항 전송 실패 · 관리자 확인 필요',
  GENERATING: '브리프 생성 진행 중', GENERATION_FAILED: '브리프 생성 오류 · 관리자 확인 필요', SEASON_ENDED: '종료된 시즌 · 조회만 가능', DISABLED: 'BRIEF 연동이 꺼져 있습니다',
}

export function BriefEditionSection(props: Props) {
  const { selection, update, invalidLink, clearLink } = props.navigation
  return <details className="brief-edition" open={selection.editionOpen} onToggle={(event) => update({ editionOpen: event.currentTarget.open })}>
    <summary>저장된 브리프</summary>
    {selection.editionOpen && (invalidLink
      ? <p role="alert">브리프 링크가 올바르지 않습니다. <button type="button" onClick={clearLink}>링크 선택 지우기</button></p>
      : <BriefEditionResults {...props} />)}
  </details>
}

function BriefEditionResults({ scope, timeZone, readOnly, onOpenSource, onGenerated, navigation, workspaceName }: Props) {
  const queryClient = useQueryClient()
  const { selectedId, baseId, previousTargetId } = navigation.selection
  const scopeKey = ['brief', scope.accountId, scope.teamId, scope.seasonId, { accessKey: scope.accessKey }]
  const latest = useQuery({ queryKey: [...scopeKey, 'latest-edition'], queryFn: ({ signal }) => getLatestEdition(scope, signal), retry: false, staleTime: 0 })
  const history = useInfiniteQuery({ queryKey: [...scopeKey, 'edition-history'], initialPageParam: null as number | null,
    queryFn: ({ pageParam, signal }) => getEditionHistory(scope, pageParam, signal),
    getNextPageParam: (page) => page.nextBeforeGeneration ?? undefined, retry: false, staleTime: 0 })
  const selected = useQuery({ queryKey: [...scopeKey, 'edition', selectedId], enabled: Boolean(selectedId),
    queryFn: ({ signal }) => getEdition(scope, selectedId, signal), retry: false, staleTime: 0 })
  const readiness = useQuery({ queryKey: [...scopeKey, 'generation-readiness'],
    queryFn: ({ signal }) => getGenerationReadiness(scope, signal), retry: false, staleTime: 0 })
  const shown = selectedId ? selected : latest
  const edition = shown.isError ? undefined : shown.data
  const delivery = useQuery({ queryKey: [...scopeKey, 'edition-delivery-status', edition?.editionId], enabled: Boolean(edition),
    queryFn: ({ signal }) => getEditionDeliveryStatus(scope, edition!.editionId, signal), retry: false, staleTime: 0 })
  const previousMode = Boolean(edition && previousTargetId === edition.editionId)
  const previous = useQuery({ queryKey: [...scopeKey, 'previous-week-edition', edition?.editionId], enabled: previousMode,
    queryFn: ({ signal }) => getPreviousWeekEdition(scope, edition!.editionId, signal), retry: false, staleTime: 0 })
  const effectiveBaseId = previousMode ? (previous.isError ? '' : previous.data?.editionId ?? '') : baseId
  const comparison = useQuery({ queryKey: [...scopeKey, 'edition-comparison', effectiveBaseId, edition?.editionId], enabled: Boolean(effectiveBaseId && edition),
    queryFn: ({ signal }) => compareEditions(scope, effectiveBaseId, edition!.editionId, signal), retry: false, staleTime: 0 })
  const generation = useMutation({ mutationFn: () => generateEdition(scope), retry: false,
    onSuccess: async () => {
      navigation.update({ selectedId: '', baseId: '', previousTargetId: '' }); onGenerated()
      await Promise.all([queryClient.invalidateQueries({ queryKey: [...scopeKey, 'latest-edition'] }),
        queryClient.invalidateQueries({ queryKey: [...scopeKey, 'edition-history'] })])
    }, onSettled: () => Promise.all([queryClient.invalidateQueries({ queryKey: [...scopeKey, 'generation-readiness'] }),
      queryClient.invalidateQueries({ queryKey: [...scopeKey, 'edition-delivery-status'] })]) })
  const accessError = [latest.error, history.error, selected.error, comparison.error, readiness.error, delivery.error, generation.error, previousMode ? previous.error : null]
    .find((error) => error instanceof ApiError && (error.status === 401 || error.status === 403))
  const refresh = () => {
    navigation.update({ selectedId: '', baseId: '', previousTargetId: '' }); generation.reset()
    void queryClient.resetQueries({ queryKey: scopeKey })
  }
  if (accessError) return <p role="alert">{accessError.message}{' '}
    <button type="button" onClick={refresh}>브리프 조회 권한 다시 확인</button></p>
  const entries = history.isError ? [] : history.data?.pages.flatMap((page) => page.editions) ?? []
  const ready = readiness.isError ? undefined : readiness.data
  const missing = shown.error instanceof ApiError && shown.error.code === 'BRIEF_EDITION_NOT_FOUND'
  const time = edition ? new Intl.DateTimeFormat('ko-KR', { timeZone: edition.zoneId, dateStyle: 'short', timeStyle: 'short' }) : null
  const readinessTime = new Intl.DateTimeFormat('ko-KR', { timeZone, dateStyle: 'short', timeStyle: 'short' })
  const deliveryData = delivery.isError ? undefined : delivery.data
  const comparisonData = comparison.isError ? undefined : comparison.data
  return <section aria-label="저장된 브리프">
    <p>생성 당시 내용을 저장한 브리프입니다. 이후 변경사항은 반영되지 않습니다.</p>
    <section className="brief-readiness" aria-label="브리프 생성 준비 상태">
      <strong>{ready ? readinessText[ready.status] : readiness.isError ? '생성 준비 상태를 확인하지 못했습니다.' : '생성 준비 상태 확인 중…'}</strong>
      {ready && <>
        <p>전달 대기 {ready.pendingCount}건 · 전달 실패 {ready.failedCount}건</p>
        <small>{ready.lastDeliveredAt ? `마지막 전달 성공: ${readinessTime.format(new Date(ready.lastDeliveredAt))}` : '전달 성공 기록 없음'}
          {' '}· 확인 {readinessTime.format(new Date(ready.checkedAt))} ({timeZone})</small>
      </>}
      <button type="button" disabled={readiness.isFetching} onClick={() => void readiness.refetch()}>전달 상태 새로고침</button>
      <p className="brief-note">기록된 변경사항의 전달 상태입니다. 새 변경이나 연결 상황은 생성 요청 시 다시 확인합니다.</p>
    </section>
    <div className="brief-pagination">
      <button type="button" disabled={latest.isFetching || generation.isPending} onClick={refresh}>최신 브리프 조회</button>
      <button type="button" disabled={readOnly || ready?.status !== 'READY' || readiness.isFetching || generation.isPending || shown.isFetching}
        onClick={() => generation.mutate()}>{generation.isPending ? '이번 주 브리프 생성 중…' : '이번 주 브리프 생성'}</button>
    </div>
    {readOnly ? <p>종료된 시즌은 저장된 브리프만 조회할 수 있습니다.</p>
      : <p className="brief-note">시즌 시간대의 이번 주 브리프를 생성합니다. 기존 브리프를 덮어쓰지 않으며 같은 상태는 재사용합니다.</p>}
    {generation.isError && <p role="alert">{generation.error.message} 전달 상태와 최신 브리프를 확인한 뒤 다시 요청해 주세요.</p>}
    {generation.isSuccess && <p role="status">{generation.data.created ? '새 브리프를 생성했습니다.' : '기존 브리프를 재사용했습니다.'} 생성 번호 {generation.data.generation}</p>}
    <div className="brief-filters">
      <label>조회할 브리프<select value={selectedId} onChange={(event) => { navigation.update({ selectedId: event.target.value, baseId: '', previousTargetId: '' }) }}>
        <option value="">최신 브리프</option>
        {selectedId && !entries.some((entry) => entry.editionId === selectedId) && <option value={selectedId}>
          {edition ? `${edition.weekStart} 시작 주 · 생성 번호 ${edition.generation}` : '선택한 브리프 확인 중'}
        </option>}
        {entries.map((entry) => <option key={entry.editionId} value={entry.editionId}>{entry.weekStart} 시작 주 · 생성 번호 {entry.generation} · {entry.itemCount}건</option>)}
      </select></label>
      <label>비교 기준 브리프<select value={previousMode ? 'previous-week' : baseId} disabled={!edition} onChange={(event) => { navigation.update({ baseId: event.target.value, previousTargetId: '' }) }}>
        <option value="">비교하지 않음</option>
        {baseId && !previousMode && !entries.some((entry) => entry.editionId === baseId) && <option value={baseId}>선택한 비교 기준</option>}
        {previousMode && <option value="previous-week">선택한 브리프의 지난주</option>}
        {entries.filter((entry) => entry.editionId !== edition?.editionId).map((entry) => <option key={entry.editionId} value={entry.editionId}>
          {entry.weekStart} 시작 주 · 생성 번호 {entry.generation}</option>)}
      </select></label>
    </div>
    <button type="button" disabled={!edition || (previousMode && previous.isFetching)} onClick={() => {
      navigation.update({ previousTargetId: edition!.editionId, baseId: '' }); if (previousMode) void previous.refetch()
    }}>지난주와 바로 비교</button>
    {previousMode && <div>
      {previous.isPending && <p role="status">선택한 브리프의 지난주 마지막 브리프를 찾고 있습니다.</p>}
      {previous.isError && <p role={previous.error instanceof ApiError && previous.error.code === 'BRIEF_EDITION_NOT_FOUND' ? 'status' : 'alert'}>
        {previous.error instanceof ApiError && previous.error.code === 'BRIEF_EDITION_NOT_FOUND'
          ? '선택한 브리프와 같은 시간대의 지난주 브리프가 없습니다.' : '지난주 브리프를 불러오지 못했습니다. 다시 비교해 주세요.'}</p>}
    </div>}
    {history.isError && <p role="alert">지난 브리프 목록을 불러오지 못했습니다. <button type="button" onClick={() => void history.refetch()}>이력 다시 조회</button></p>}
    {history.hasNextPage && <button type="button" disabled={history.isFetching} onClick={() => void history.fetchNextPage()}>이전 브리프 더보기</button>}
    {shown.isPending && <p role="status">저장된 브리프를 불러오고 있습니다.</p>}
    {missing && <p role="status">{selectedId ? '선택한 브리프를 찾을 수 없습니다.' : '아직 저장된 브리프가 없습니다.'}</p>}
    {shown.isError && !missing && <p role="alert">저장된 브리프를 불러오지 못했습니다. {shown.error.message}</p>}
    {edition && time && <>
      <h3>{edition.weekStart} 시작 주 · 생성 번호 {edition.generation}</h3>
      <p>생성 {time.format(new Date(edition.generatedAt))} ({edition.zoneId})</p>
      <section className="brief-delivery-status" aria-label="저장 이후 변경 확인">
        <strong>{deliveryData ? deliveryStatusText[deliveryData.status] : delivery.isError
          ? '추가 전달 기록을 불러오지 못했습니다.' : '추가 전달 기록 확인 중…'}</strong>
        {deliveryData && <small>확인 {readinessTime.format(new Date(deliveryData.checkedAt))} ({timeZone})</small>}
        {deliveryData?.status === 'ADDITIONAL_DELIVERIES' && <p>{readOnly
          ? '종료된 시즌은 현재 점검 항목에서 이후 상태를 확인해 주세요.'
          : '현재 점검 항목을 확인하고, 생성 준비가 끝나면 이번 주 브리프를 요청할 수 있습니다.'}</p>}
        <button type="button" disabled={delivery.isFetching} onClick={() => void delivery.refetch()}>저장 이후 변경 새로고침</button>
        <details className="brief-evidence"><summary>확인 기준 보기</summary>
          <p className="brief-note">이 브리프를 생성하거나 재사용한 마지막 성공 요청을 기준으로 확인합니다.
            추가 전달이 있어도 브리프 항목은 같을 수 있으며, 추가 전달이 없어도 원본 전체의 반영을 보장하지 않습니다.</p>
        </details>
      </section>
      <details className="brief-evidence"><summary>집계 기준 보기</summary>
        <p className="brief-note">집계 구간: {time.format(new Date(edition.windowStart))} 이상 ~ {time.format(new Date(edition.windowEnd))} 미만
          {' '}· 수신 커서 {edition.sourceCursor} · 규칙 {edition.ruleVersion}</p></details>
      {effectiveBaseId && <section className="brief-comparison" aria-label="브리프 비교 결과">
        <h4>선택한 두 브리프의 차이</h4>
        <p className="brief-note">‘제외’는 비교 대상에 포함되지 않았다는 뜻입니다. 문제가 해결됐다는 뜻은 아닙니다.</p>
        {comparison.isPending && <p role="status">브리프 차이를 불러오고 있습니다.</p>}
        {comparison.isError && <p role="alert">비교 결과를 불러오지 못했습니다. <button type="button" onClick={() => void comparison.refetch()}>비교 다시 조회</button></p>}
        {comparisonData && <BriefSources scope={scope} items={[...comparisonData.added, ...comparisonData.removed, ...comparisonData.changed.map((change) => change.after)]} onOpen={onOpenSource}>
          <p>기준: {comparisonData.from.weekStart} · 생성 번호 {comparisonData.from.generation} → 대상: {comparisonData.to.weekStart} · 생성 번호 {comparisonData.to.generation}</p>
          {comparisonData.from.ruleVersion !== comparisonData.to.ruleVersion && <p className="brief-note">선정 기준이 달라 항목에 차이가 있을 수 있습니다.</p>}
          <p>추가 {comparisonData.added.length}건 · 제외 {comparisonData.removed.length}건 · 변경 {comparisonData.changed.length}건</p>
          {comparisonData.added.length + comparisonData.removed.length + comparisonData.changed.length === 0 && <p>저장된 항목의 차이가 없습니다.</p>}
          {comparisonData.added.length > 0 && <section aria-label="브리프에 추가됨"><h5>추가</h5><EditionItems items={comparisonData.added} zoneId={comparisonData.to.zoneId} readOnly={readOnly} /></section>}
          {comparisonData.removed.length > 0 && <section aria-label="브리프에서 제외됨"><h5>제외</h5><EditionItems items={comparisonData.removed} zoneId={comparisonData.from.zoneId} readOnly={readOnly} /></section>}
          {comparisonData.changed.map((change) => <section className="brief-change" key={`${change.after.reasonCode}:${change.after.sourceReference}`} aria-label="브리프 항목 변경">
            <h5>변경된 항목</h5>
            {change.before.section !== change.after.section && <p>분류 변경: {change.before.section ? sectionNames[change.before.section] : '분류 미기록'} → {change.after.section ? sectionNames[change.after.section] : '분류 미기록'}</p>}
            <div className="brief-change-columns"><div><h6>변경 전</h6><EditionItems items={[change.before]} zoneId={comparisonData.from.zoneId} readOnly={readOnly} /></div>
              <div><h6>변경 후</h6><EditionItems items={[change.after]} zoneId={comparisonData.to.zoneId} readOnly={readOnly} /></div></div>
          </section>)}
        </BriefSources>}
      </section>}
      <BriefSources scope={scope} items={edition.items} onOpen={onOpenSource}>
        <BriefEditionActions key={edition.editionId} edition={edition} workspaceName={workspaceName} scope={scope} loading={shown.isFetching} />
        <p className="brief-note">업무명과 이동 대상은 현재 BATON 정보입니다. 저장된 브리프의 내용은 그대로 유지합니다.</p>
        {edition.items.length === 0 ? <p>이 브리프에 선정된 점검 항목이 없습니다.</p>
          : editionSections
            .map((section) => {
              const items = edition.items.filter((item) => item.section === section.value)
              return items.length > 0 && <section key={section.value ?? 'legacy'} aria-label={section.label}>
                <h4>{section.label} · {items.length}건</h4><EditionItems items={items} zoneId={edition.zoneId} readOnly={readOnly} />
              </section>
            })}
      </BriefSources>
    </>}
  </section>
}

function EditionItems({ items, zoneId, readOnly }: { items: BriefEdition['items']; zoneId: string; readOnly: boolean }) {
  const time = new Intl.DateTimeFormat('ko-KR', { timeZone: zoneId, dateStyle: 'short', timeStyle: 'short' })
  return <ul className="brief-items">{items.map((item) => <li key={`${item.reasonCode}:${item.sourceReference}`}>
    <div><strong>{attentionReasons[item.reasonCode as AttentionItem['reasonCode']]}</strong>
      <span>{item.severity === 'HIGH' ? '높음' : '보통'} · {item.status === 'ACTIVE' ? '미해결' : '해결'}</span></div>
    <BriefSourceLink item={item} readOnly={readOnly} />
    <small>상태 기록 {time.format(new Date(item.observedAt))} ({zoneId})</small>
    <details className="brief-evidence"><summary>원본 기록 보기</summary>
      <small>원본 항목 ID <code>{item.sourceReference}</code></small>
      <small>{item.aggregateRevision === null ? '이전 브리프: 변경 번호·누락 이력 미기록'
        : `원본 변경 번호 ${item.aggregateRevision} · ${item.revisionGap ? '누락 이력 있음' : '누락 이력 없음'}`}</small>
    </details>
  </li>)}</ul>
}
