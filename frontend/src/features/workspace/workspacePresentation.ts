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
