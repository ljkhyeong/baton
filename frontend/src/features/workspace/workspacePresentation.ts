import { ApiClientError, ApiError } from '@/shared/api/ApiError'
import type {
  HandoffCategory,
  Member,
  RoleHandoff,
  RoutinePhase,
} from './types'

export const phaseCopy = {
  BEFORE: '모임 전',
  DURING: '모임 중',
  AFTER: '모임 후',
} satisfies Record<RoutinePhase, string>

export const categoryCopy = {
  RESPONSIBILITY: '책임',
  ROUTINE: '반복 업무',
  RESOURCE: '자료',
  ADVICE: '조언',
} satisfies Record<HandoffCategory, string>

export function getMember(members: Member[], memberId?: string | null) {
  return members.find((member) => member.id === memberId)
}

export function isActiveMember(member: Member) {
  return member.deactivatedAt === null
}

export function memberDisplayName(member: Member) {
  return isActiveMember(member) ? member.name : `${member.name} · 활동 종료`
}

export function memberSelectionOptions(members: Member[], retainedMemberId?: string | null) {
  return members.filter((member) =>
    isActiveMember(member) || member.id === retainedMemberId)
}

export function latestRoleHandoff(
  roleHandoffs: RoleHandoff[],
  roleId: string,
) {
  let latest: RoleHandoff | undefined
  for (const handoff of roleHandoffs) {
    if (handoff.roleId !== roleId) continue

    const preparedOrder = handoff.preparedAt.localeCompare(latest?.preparedAt ?? '')
    if (!latest
      || preparedOrder > 0
      || (preparedOrder === 0 && handoff.id.localeCompare(latest.id) > 0)) {
      latest = handoff
    }
  }
  return latest
}

export function isRoleHandoffLocked(
  roleHandoffs: RoleHandoff[],
  roleId: string,
) {
  return latestRoleHandoff(roleHandoffs, roleId)?.status === 'TRANSFERRED'
}

export function formatLocalDate(value?: string | null) {
  if (!value) return '미정'
  const [year, month, day] = value.split('-').map(Number)
  return `${year}. ${month}. ${day}.`
}

export function mutationError(error: unknown) {
  if (error instanceof ApiError && error.code === 'WORKSPACE_CONTENT_CONFLICT') {
    return '다른 구성원이 먼저 수정했습니다. 최신 내용을 확인한 뒤 다시 시도해 주세요.'
  }
  if (error instanceof ApiError && error.code === 'ROLE_HANDOFF_STATE_CONFLICT') {
    return '역할 인수인계 상태가 먼저 바뀌었습니다. 최신 내용을 확인한 뒤 다시 시도해 주세요.'
  }
  if (error instanceof ApiError
    && error.code === 'ROLE_HANDOFF_WARNING_CONFIRMATION_REQUIRED') {
    return '미완료 항목이나 자료 없음 경고를 확인해야 인수인계를 전달할 수 있어요.'
  }
  if (error instanceof ApiError && error.code === 'IDEMPOTENCY_REPLAY_EXPIRED') {
    return '더 최신 접근 키 변경이 완료되어 이전 결과를 다시 받을 수 없습니다. 새 요청으로 다시 시도해 주세요.'
  }
  if (error instanceof ApiError || error instanceof ApiClientError) return error.message
  return '요청을 처리하지 못했습니다. 다시 시도해 주세요.'
}
