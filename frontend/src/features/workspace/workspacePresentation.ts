import { ApiError } from '@/shared/api/ApiError'
import type {
  HandoffCategory,
  Member,
  RoutinePhase,
} from './types'

export const phaseCopy = {
  BEFORE: '모임 전',
  DURING: '모임 중',
  AFTER: '모임 후',
} satisfies Record<RoutinePhase, string>

export const categoryCopy = {
  RESPONSIBILITY: '책임',
  ROUTINE: '루틴',
  RESOURCE: '자료',
  ADVICE: '조언',
} satisfies Record<HandoffCategory, string>

export function getMember(members: Member[], memberId?: string | null) {
  return members.find((member) => member.id === memberId)
}

export function formatLocalDate(value?: string | null) {
  if (!value) return '미정'
  const [year, month, day] = value.split('-').map(Number)
  if (!year || !month || !day) return value
  return `${year}. ${month}. ${day}.`
}

export function mutationError(error: unknown) {
  if (error instanceof ApiError && error.code === 'WORKSPACE_CONTENT_CONFLICT') {
    return '다른 구성원이 먼저 수정했습니다. 최신 내용을 확인한 뒤 다시 시도해 주세요.'
  }
  if (error instanceof ApiError && error.code === 'IDEMPOTENCY_REPLAY_EXPIRED') {
    return '더 최신 접근 키 변경이 완료되어 이전 결과를 다시 받을 수 없습니다. 새 요청으로 다시 시도해 주세요.'
  }
  return error instanceof Error ? error.message : '요청을 처리하지 못했습니다. 다시 시도해 주세요.'
}
