import { useMemo, type ReactNode } from 'react'
import { Icon } from '@/shared/ui/Icon'
import type {
  Decision,
  HandoffItem,
  Role,
  RoleResource,
  Season,
  WorkspaceProjection,
} from '../types'
import {
  handoffCategoryLabel,
  compareRecordSearchResults,
  isRecordSearchDateRangeValid,
  searchWorkspaceRecords,
  type RecordSearchFilters,
  type RecordSearchResult,
} from './recordSearch'

export const initialRecordSearchFilters: RecordSearchFilters = {
  query: '',
  type: 'all',
  roleId: '',
  state: 'all',
  fromDate: '',
  toDate: '',
}

const kindCopy = {
  decision: '결정',
  handoff: '인수인계',
  resource: '자료',
} satisfies Record<RecordSearchResult['kind'], string>

function formatRecordTime(value: string | null, timeZone: string) {
  if (!value) return '작성일을 알 수 없음'
  const date = new Date(value)
  return new Intl.DateTimeFormat('ko-KR', {
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    month: 'long',
    timeZone,
    year: 'numeric',
  }).format(date)
}

function resultSecondaryText(result: RecordSearchResult) {
  if (result.kind !== 'handoff' || !result.secondaryText) return result.secondaryText
  return handoffCategoryLabel[result.secondaryText as keyof typeof handoffCategoryLabel]
    ?? result.secondaryText
}

function resultActionLabel(result: RecordSearchResult) {
  if (result.kind === 'decision') return '결정 기록에서 보기'
  if (result.kind === 'handoff') return '인수인계 문서에서 보기'
  return '역할에서 보기'
}

type RecordSearchViewProps = {
  season: Season
  otherSeasons?: WorkspaceProjection[]
  scopeControl?: ReactNode
  partial?: boolean
  onOpenOtherSeason?: (result: RecordSearchResult, seasonId: string) => string
  roles: Role[]
  decisions: Decision[]
  handoffItems: HandoffItem[]
  resources: RoleResource[]
  filters: RecordSearchFilters
  onFiltersChange: (filters: RecordSearchFilters) => void
  onOpenResult: (result: RecordSearchResult) => void
}

export function RecordSearchView({
  season,
  otherSeasons = [],
  scopeControl,
  partial = false,
  onOpenOtherSeason,
  roles,
  decisions,
  handoffItems,
  resources,
  filters,
  onFiltersChange,
  onOpenResult,
}: RecordSearchViewProps) {
  const sources = useMemo(
    () => [{ season, decisions, handoffItems, resources, roles }, ...otherSeasons],
    [season, decisions, handoffItems, resources, roles, otherSeasons],
  )
  const results = useMemo(
    () => sources.flatMap(source => searchWorkspaceRecords(source, filters, source.season.timeZone)
      .map(result => ({ ...result, originSeason: source.season }))).sort(compareRecordSearchResults),
    [filters, sources],
  )
  const matchingUnknownTimeCount = useMemo(() => {
    if (!filters.fromDate && !filters.toDate) return 0
    return sources.reduce((count, source) => count + searchWorkspaceRecords(
      source, { ...filters, fromDate: '', toDate: '' }, source.season.timeZone,
    ).filter(result => !result.createdAt).length, 0)
  }, [filters, sources])
  const validDateRange = isRecordSearchDateRangeValid(filters)
  const hasFilters = filters.query !== ''
    || filters.type !== 'all'
    || filters.roleId !== ''
    || filters.state !== 'all'
    || filters.fromDate !== ''
    || filters.toDate !== ''
  const updateFilter = <Key extends keyof RecordSearchFilters>(
    key: Key,
    value: RecordSearchFilters[Key],
  ) => onFiltersChange({ ...filters, [key]: value })

  return (
    <>
      <header className="page-header">
        <div>
          <span className="eyebrow">{season.name}</span>
          <h1>기록 검색</h1>
          <p>선택한 시즌의 결정·인수인계·자료를 검색합니다.</p>
        </div>
      </header>

      <form
        className="record-search-panel"
        role="search"
        aria-label="결정, 인수인계와 자료 검색"
        onSubmit={(event) => event.preventDefault()}
      >
        {scopeControl}
        <label className="record-search-query">
          <span>무엇을 다시 찾고 있나요?</span>
          <span className="record-search-input">
            <Icon name="search" size={18} />
            <input
              data-record-search-input
              type="search"
              value={filters.query}
              placeholder="예: 모임 시간 변경, 인수인계 주의사항, 회의록"
              onChange={(event) => updateFilter('query', event.target.value)}
            />
          </span>
        </label>
        <div className="record-search-filters">
          <label>
            <span>기록 종류</span>
            <select
              value={filters.type}
              onChange={(event) =>
                updateFilter('type', event.target.value as RecordSearchFilters['type'])}
            >
              <option value="all">결정 · 인수인계 · 자료</option>
              <option value="decision">결정만</option>
              <option value="handoff">인수인계만</option>
              <option value="resource">자료만</option>
            </select>
          </label>
          <label>
            <span>관련 역할</span>
            <select
              aria-label="관련 역할"
              value={filters.roleId}
              onChange={(event) => updateFilter('roleId', event.target.value)}
            >
              <option value="">모든 역할</option>
              {sources.map(source => <optgroup key={source.season.id} label={source.season.name}>
                {source.roles.map(role => <option key={role.id} value={role.id}>{source.season.id === season.id && sources.length === 1 ? role.name : `${source.season.name} · ${role.name}`}</option>)}
              </optgroup>)}
            </select>
          </label>
          <label>
            <span>상태</span>
            <select
              value={filters.state}
              onChange={(event) =>
                updateFilter('state', event.target.value as RecordSearchFilters['state'])}
            >
              <option value="all">전체 기록</option>
              <option value="active">보관하지 않은 기록</option>
              <option value="archived">보관 기록</option>
            </select>
          </label>
          <label>
            <span>시작일</span>
            <input
              type="date"
              value={filters.fromDate}
              max={filters.toDate || undefined}
              onChange={(event) => updateFilter('fromDate', event.target.value)}
            />
          </label>
          <label>
            <span>종료일</span>
            <input
              type="date"
              value={filters.toDate}
              min={filters.fromDate || undefined}
              onChange={(event) => updateFilter('toDate', event.target.value)}
            />
          </label>
        </div>
        <div className="record-search-footer">
          <span>
            날짜는 각 시즌의 시간대를 기준으로 검색합니다. 현재 시즌의 시간대는 <strong>{season.timeZone}</strong>입니다.
          </span>
          <button
            type="button"
            className="secondary-button"
            disabled={!hasFilters}
            onClick={() => onFiltersChange(initialRecordSearchFilters)}
          >
            검색 조건 지우기
          </button>
        </div>
      </form>

      {!validDateRange ? (
        <div className="record-search-error" role="alert">
          시작일은 종료일보다 늦을 수 없습니다.
        </div>
      ) : (
        <section className="record-search-results" aria-labelledby="record-search-result-title">
          <div className="record-search-summary">
            <div>
              <span className="section-kicker">최근 기록</span>
              <h2 id="record-search-result-title">{partial ? '불러온 시즌에서 ' : ''}{results.length}개의 기록을 찾았어요</h2>
            </div>
            <p aria-live="polite" aria-atomic="true">
              검색 결과 {results.length}개.{' '}
              {matchingUnknownTimeCount > 0
                ? `작성일을 알 수 없는 이전 기록 ${matchingUnknownTimeCount}개는 날짜 검색에서 제외했습니다.`
                : '최신 기록부터 표시합니다.'}
            </p>
          </div>

          {results.length ? (
            <ol className="record-timeline">
              {results.map((result) => (
                <li key={`${result.originSeason.id}:${result.key}`}>
                  <article className={`record-search-card kind-${result.kind}`}>
                    <div className="record-search-card-meta">
                      <span className="record-kind">{kindCopy[result.kind]}</span>
                      <span>{result.originSeason.name}</span>
                      <time dateTime={result.createdAt ?? undefined}>
                        {formatRecordTime(result.createdAt, result.originSeason.timeZone)}
                      </time>
                      {result.archivedAt && <span className="record-archived">보관됨</span>}
                    </div>
                    <h3>{result.title}</h3>
                    <dl>
                      {result.authorName && (
                        <div>
                          <dt>작성자</dt>
                          <dd>{result.authorName}</dd>
                        </div>
                      )}
                      <div>
                        <dt>{result.primaryLabel}</dt>
                        <dd>{result.primaryText}</dd>
                      </div>
                      {result.secondaryLabel && result.secondaryText && (
                        <div>
                          <dt>{result.secondaryLabel}</dt>
                          <dd>{resultSecondaryText(result)}</dd>
                        </div>
                      )}
                    </dl>
                    <div className="record-search-roles" aria-label="관련 역할">
                      {result.roleNames.map((name, index) => (
                        <span key={`${result.roleIds[index] ?? name}:${index}`}>{name}</span>
                      ))}
                    </div>
                    <div className="record-search-actions">
                      {result.externalUrl && (
                        <a
                          href={result.externalUrl}
                          target="_blank"
                          rel="noopener noreferrer"
                          aria-label={`${result.title} 자료 새 창에서 열기`}
                        >
                          자료 새 창에서 열기
                        </a>
                      )}
                      {!result.archivedAt && result.originSeason.id !== season.id && onOpenOtherSeason && (
                        <a href={onOpenOtherSeason(result, result.originSeason.id)} target="_blank" rel="noopener noreferrer"
                          aria-label={`${result.title} 원본 시즌 새 창에서 보기`}>원본 시즌 새 창에서 보기</a>
                      )}
                      {!result.archivedAt && result.originSeason.id === season.id && (
                        <button
                          type="button"
                          aria-label={`${result.title} ${resultActionLabel(result)}`}
                          onClick={() => onOpenResult(result)}
                        >
                          {resultActionLabel(result)}
                        </button>
                      )}
                    </div>
                  </article>
                </li>
              ))}
            </ol>
          ) : (
            <div className="empty-state record-search-empty">
              <Icon name="search" size={28} />
              <strong>조건에 맞는 기록이 없어요</strong>
              <p>
                단어를 줄이거나 역할·상태·기간 조건을 넓혀 보세요.
                자료 링크 주소와 외부 문서 본문은 검색하지 않습니다.
              </p>
            </div>
          )}
        </section>
      )}
    </>
  )
}
