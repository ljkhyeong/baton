import type { operations } from '@/generated/api'

export type AttentionSummary = operations['getBriefAttentionSummary']['responses'][200]['content']['application/json']
export type AttentionPage = operations['getBriefAttentionItems']['responses'][200]['content']['application/json']
export type AttentionItem = AttentionPage['items'][number]
export type AttentionCursor = NonNullable<AttentionPage['nextCursor']>
export type AttentionFilter = {
  status: AttentionItem['status']
  severity?: AttentionItem['severity']
  revisionGap?: boolean
}
export type BriefScope = { accountId: string; teamId: string; seasonId: string; accessKey: string }

export const attentionReasons = {
  HANDOFF_BLOCKED: '인수인계 진행이 막힘',
  ROUTINE_MISSED: '운영 루틴 누락',
  DECISION_FOLLOW_UP_OVERDUE: '결정 후속 조치 지연',
  ROLE_UNASSIGNED: '역할 담당자 없음',
  ROLE_SUCCESSOR_MISSING: '다음 담당자 없음',
  ROLE_PREPARATION_INCOMPLETE: '역할 준비 미완료',
  ROUTINE_REPEATEDLY_OVERDUE: '운영 루틴 반복 지연',
  HANDOFF_INCOMPLETE: '인수인계 미완료',
} satisfies Record<AttentionItem['reasonCode'], string>
