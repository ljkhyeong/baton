import type {
  Decision,
  HandoffItem,
  Role,
  RoleResource,
} from '@/features/workspace/types'

export type RecordSearchType = 'all' | 'decision' | 'handoff' | 'resource'
export type RecordSearchState = 'all' | 'active' | 'archived'

export type RecordSearchFilters = {
  query: string
  type: RecordSearchType
  roleId: string
  state: RecordSearchState
  fromDate: string
  toDate: string
}

export type RecordSearchResult = {
  key: string
  id: string
  kind: Exclude<RecordSearchType, 'all'>
  title: string
  primaryLabel: string
  primaryText: string
  secondaryLabel?: string
  secondaryText?: string
  createdAt: string | null
  archivedAt: string | null
  roleId: string
  roleIds: string[]
  roleNames: string[]
  authorName?: string
  externalUrl?: string
  searchableText: string
}

export type RecordSearchSource = {
  decisions: Decision[]
  handoffItems: HandoffItem[]
  resources: RoleResource[]
  roles: Role[]
}

export const handoffCategoryLabel = {
  RESPONSIBILITY: '책임',
  ROUTINE: '반복 운영',
  RESOURCE: '자료',
  ADVICE: '조언',
} satisfies Record<HandoffItem['category'], string>

function normalizeSearchText(value: string) {
  return value
    .normalize('NFKC')
    .toLocaleLowerCase('ko-KR')
    .replace(/\s+/g, ' ')
    .trim()
}

function recordCalendarDate(value: string, timeZone: string) {
  const parts = new Intl.DateTimeFormat('en-US', {
    day: '2-digit',
    month: '2-digit',
    timeZone,
    year: 'numeric',
  }).formatToParts(new Date(value))
  const part = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((candidate) => candidate.type === type)?.value ?? ''
  return `${part('year')}-${part('month')}-${part('day')}`
}

function roleName(roleId: string, rolesById: Map<string, Role>) {
  return rolesById.get(roleId)?.name ?? '삭제되었거나 찾을 수 없는 역할'
}

function buildSearchResults({
  decisions,
  handoffItems,
  resources,
  roles,
}: RecordSearchSource) {
  const rolesById = new Map(roles.map((role) => [role.id, role]))
  const decisionResults: RecordSearchResult[] = decisions.map((decision) => {
    const roleNames = decision.roleIds.map((roleId) => roleName(roleId, rolesById))
    return {
      key: `decision:${decision.id}`,
      id: decision.id,
      kind: 'decision',
      title: decision.title,
      primaryLabel: '이유',
      primaryText: decision.reason,
      secondaryLabel: decision.alternative ? '검토한 다른 선택' : undefined,
      secondaryText: decision.alternative || undefined,
      createdAt: decision.createdAt,
      archivedAt: decision.archivedAt,
      roleId: decision.roleIds[0] ?? '',
      roleIds: decision.roleIds,
      roleNames,
      authorName: decision.authorName,
      searchableText: normalizeSearchText([
        decision.title,
        decision.reason,
        decision.alternative,
        decision.authorName,
        ...roleNames,
      ].join(' ')),
    }
  })
  const handoffResults: RecordSearchResult[] = handoffItems.map((item) => {
    const ownerRoleName = roleName(item.roleId, rolesById)
    return {
      key: `handoff:${item.id}`,
      id: item.id,
      kind: 'handoff',
      title: item.label,
      primaryLabel: '바통 상태',
      primaryText: item.completed ? '준비 완료한 항목' : '아직 준비가 필요한 항목',
      secondaryLabel: '분류',
      secondaryText: item.category,
      createdAt: item.createdAt,
      archivedAt: item.archivedAt,
      roleId: item.roleId,
      roleIds: [item.roleId],
      roleNames: [ownerRoleName],
      searchableText: normalizeSearchText([
        item.label,
        item.category,
        handoffCategoryLabel[item.category],
        ownerRoleName,
      ].join(' ')),
    }
  })
  const resourceResults: RecordSearchResult[] = resources.map((resource) => {
    const ownerRoleName = roleName(resource.roleId, rolesById)
    return {
      key: `resource:${resource.id}`,
      id: resource.id,
      kind: 'resource',
      title: resource.title,
      primaryLabel: '사용 맥락',
      primaryText: resource.description || '아직 자료 설명을 남기지 않았습니다.',
      createdAt: resource.createdAt,
      archivedAt: null,
      roleId: resource.roleId,
      roleIds: [resource.roleId],
      roleNames: [ownerRoleName],
      externalUrl: resource.url,
      searchableText: normalizeSearchText([
        resource.title,
        resource.description ?? '',
        ownerRoleName,
      ].join(' ')),
    }
  })
  return [...decisionResults, ...handoffResults, ...resourceResults]
}

export function isRecordSearchDateRangeValid(filters: RecordSearchFilters) {
  return !filters.fromDate || !filters.toDate || filters.fromDate <= filters.toDate
}

export function searchWorkspaceRecords(
  source: RecordSearchSource,
  filters: RecordSearchFilters,
  timeZone: string,
) {
  if (!isRecordSearchDateRangeValid(filters)) return []

  const tokens = normalizeSearchText(filters.query).split(' ').filter(Boolean)
  return buildSearchResults(source)
    .filter((result) => filters.type === 'all' || result.kind === filters.type)
    .filter((result) => !filters.roleId || result.roleIds.includes(filters.roleId))
    .filter((result) => {
      if (filters.state === 'all') return true
      return filters.state === 'archived'
        ? Boolean(result.archivedAt)
        : !result.archivedAt
    })
    .filter((result) => tokens.every((token) => result.searchableText.includes(token)))
    .filter((result) => {
      if (!filters.fromDate && !filters.toDate) return true
      if (!result.createdAt) return false
      const calendarDate = recordCalendarDate(result.createdAt, timeZone)
      return (!filters.fromDate || calendarDate >= filters.fromDate)
        && (!filters.toDate || calendarDate <= filters.toDate)
    })
    .sort((left, right) => {
      if (left.createdAt && right.createdAt) {
        const leftTime = Date.parse(left.createdAt)
        const rightTime = Date.parse(right.createdAt)
        const timeOrder = Number.isFinite(leftTime) && Number.isFinite(rightTime)
          ? rightTime - leftTime
          : right.createdAt.localeCompare(left.createdAt)
        if (timeOrder !== 0) return timeOrder
      } else if (left.createdAt) {
        return -1
      } else if (right.createdAt) {
        return 1
      }
      const kindOrder = left.kind.localeCompare(right.kind, 'en')
      if (kindOrder !== 0) return kindOrder
      const titleOrder = left.title.localeCompare(right.title, 'ko')
      return titleOrder !== 0 ? titleOrder : left.id.localeCompare(right.id)
    })
}
