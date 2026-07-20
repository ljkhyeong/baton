export type ViewKey = 'today' | 'roles' | 'rhythm' | 'memory' | 'handoff'

export type Member = {
  id: string
  name: string
  initials: string
  tone: string
}

export type Role = {
  id: string
  name: string
  purpose: string
  personId?: string
  nextPersonId?: string
  term: string
  progress: number
  responsibilities: string[]
  routines: string[]
  risk?: string
}

export type RoutineStatus = 'done' | 'active' | 'waiting' | 'late'

export type Routine = {
  id: string
  title: string
  phase: '모임 전' | '모임 중' | '모임 후'
  due: string
  ownerRoleId: string
  status: RoutineStatus
  detail: string
}

export type Decision = {
  id: string
  title: string
  reason: string
  alternative: string
  date: string
  author: string
  roleIds: string[]
}

export type HandoffItem = {
  id: string
  roleId: string
  label: string
  category: '책임' | '루틴' | '자료' | '조언'
  done: boolean
}
