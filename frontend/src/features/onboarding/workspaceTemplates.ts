import type { CreateWorkspaceRequest } from '@/features/workspace/types'

export const workspaceTemplates: Record<NonNullable<CreateWorkspaceRequest['template']>, {
  name: string; roles: string[]; routines: string[]
}> = {
  STUDY_V1: { name: '스터디 기본 구성', roles: ['진행 담당', '학습 준비 담당', '기록 담당'],
    routines: ['모임 안건 공유', '학습 자료 준비', '회고와 결정 정리'] },
  TEAM_V1: { name: '팀 운영 기본 구성', roles: ['운영 담당', '일정 담당', '기록 담당'],
    routines: ['회의 안건 정리', '진행 상태 확인', '결정과 후속 작업 정리'] },
}
