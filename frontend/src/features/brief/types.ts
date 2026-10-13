import type { operations } from '@/generated/api'
import { pilotCalendarDate } from '@/features/workspace/seasonCalendar'
import { calendarDayNumber } from '@/shared/lib/calendarDate'
import { formatInstantDate } from '@/shared/lib/dateTimeFormat'

export type WeeklyResolutions = operations['getBriefWeeklyResolutions']['responses'][200]['content']['application/json']
export type AttentionSummary = operations['getBriefAttentionSummary']['responses'][200]['content']['application/json']
export type AttentionPage = operations['getBriefAttentionItems']['responses'][200]['content']['application/json']
export type AttentionItem = AttentionPage['items'][number]
export type AttentionCursor = NonNullable<AttentionPage['nextCursor']>
export type AttentionTransitions = operations['getBriefAttentionTransitions']['responses'][200]['content']['application/json']
export type BriefEdition = operations['getLatestBriefEdition']['responses'][200]['content']['application/json']
export type BriefEditionHistory = operations['getBriefEditionHistory']['responses'][200]['content']['application/json']
export type BriefComparison = operations['compareBriefEditions']['responses'][200]['content']['application/json']
export type BriefSources = operations['queryBriefSources']['responses'][200]['content']['application/json']
export type BriefSource = BriefSources['sources'][number]
export type BriefReadiness = operations['getBriefGenerationReadiness']['responses'][200]['content']['application/json']
export type BriefDeliveryStatus = operations['getBriefEditionDeliveryStatus']['responses'][200]['content']['application/json']
export type BriefGeneration = operations['generateBriefEdition']['responses'][201]['content']['application/json']
export type AttentionFilter = {
  status: AttentionItem['status']
  severity?: AttentionItem['severity']
  revisionGap?: boolean
}
export type BriefScope = { accountId: string; teamId: string; seasonId: string; accessKey: string }

export const sectionNames = { CURRENT_WEEK: '이번 주 변경', CARRY_OVER: '이전 주부터 미해결' } as const

export const editionSections = [
  { value: 'CURRENT_WEEK', label: sectionNames.CURRENT_WEEK },
  { value: 'CARRY_OVER', label: sectionNames.CARRY_OVER },
  { value: null, label: '이전 주간 요약 · 분류 미기록' },
] as const

export const severityLabel = (severity: string) => severity === 'HIGH' ? '높음' : '보통'
export const statusLabel = (status: string) => status === 'ACTIVE' ? '미해결' : '해결'

export function editionItemEvidence({ aggregateRevision, revisionGap }: Pick<BriefEdition['items'][number], 'aggregateRevision' | 'revisionGap'>) {
  return aggregateRevision === null ? '이전 주간 요약: 변경 번호·누락 여부 미기록'
    : `원본 변경 번호 ${aggregateRevision} · ${revisionGap ? '변경 기록 누락 있음' : '변경 기록 누락 없음'}`
}

// 미해결 시작일과 기준 시각까지 지난 날 수를 같은 시간대의 달력 날짜로 센다. 시작일이 기준일과 같거나 늦으면 날 수를 붙이지 않는다.
// 누락이 있으면 BRIEF가 누락 이후부터 센 값이므로 이전 기간이 빠졌다고 밝힌다. 이전 BRIEF가 값을 주지 않으면 표시하지 않는다.
export function activeSinceText({ activeSince, revisionGap }: { activeSince: string | null; revisionGap: boolean | null },
  timeZone: string, reference: Date, referenceLabel = '') {
  if (activeSince === null) return null
  const days = calendarDayNumber(pilotCalendarDate(reference, timeZone)) - calendarDayNumber(pilotCalendarDate(new Date(activeSince), timeZone))
  return [`미해결 시작 ${formatInstantDate(activeSince, timeZone)}`, days > 0 && `${referenceLabel}${days}일 지남`,
    revisionGap && '누락 이전 기간 제외'].filter(Boolean).join(' · ')
}

export const attentionReasons = {
  HANDOFF_BLOCKED: '인수인계 진행이 막힘',
  ROUTINE_MISSED: '반복 업무 누락',
  DECISION_FOLLOW_UP_OVERDUE: '결정 후속 조치 지연',
  ROLE_UNASSIGNED: '역할 담당자 없음',
  ROLE_SUCCESSOR_MISSING: '다음 담당자 없음',
  ROLE_PREPARATION_INCOMPLETE: '인수인계 준비 미완료',
  ROUTINE_REPEATEDLY_OVERDUE: '반복 업무 지연 누적',
  HANDOFF_INCOMPLETE: '인수인계 미완료',
} satisfies Record<AttentionItem['reasonCode'], string>
