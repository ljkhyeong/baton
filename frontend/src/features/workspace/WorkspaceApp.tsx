import { useEffect, useRef, useState } from 'react'
import type { FormEvent, ReactNode } from 'react'
import { ApiError } from '@/shared/api/ApiError'
import { Icon } from '@/shared/ui/Icon'
import { saveAccessKey } from './api'
import type { WorkspaceScope } from './api'
import {
  clearPendingAccessKeyRotation,
  idempotencyKeyForAccessKeyRotation,
  pendingAccessKeyRotation,
} from './pendingAccessKeyChange'
import {
  clearPendingContentCreation,
  hasPendingContentCreation,
  prepareContentCreation,
} from './pendingContentCreation'
import type { ContentCreationOperation } from './pendingContentCreation'
import {
  useCreateDecisionMutation,
  useCreateHandoffItemMutation,
  useCreateRoleMutation,
  useCreateRoleResourceMutation,
  useCreateRoutineMutation,
  useCreateSeasonRoundMutation,
  useHandoffCompletionMutation,
  useRoutineExecutionCompletionMutation,
  useRotateAccessKeyMutation,
  useUpdateRoleMutation,
  useUpdateRoleResourceMutation,
  useUpdateRoutineMutation,
  useWorkspaceQuery,
} from './queries'
import type {
  CreateDecisionRequest,
  CreateHandoffItemRequest,
  CreateRoleRequest,
  CreateRoleResourceRequest,
  CreateRoutineRequest,
  CreateSeasonRoundRequest,
  Decision,
  HandoffCategory,
  HandoffItem,
  Member,
  Role,
  RoleResource,
  Routine,
  RoutineExecution,
  RoutinePhase,
  RoutineStatus,
  Season,
  SeasonRound,
  UpdateRoleRequest,
  UpdateRoleResourceRequest,
  UpdateRoutineRequest,
  ViewKey,
  WorkspaceProjection,
} from './types'

const navItems: { key: ViewKey; label: string; icon: Parameters<typeof Icon>[0]['name'] }[] = [
  { key: 'today', label: '오늘', icon: 'today' },
  { key: 'roles', label: '역할', icon: 'roles' },
  { key: 'rhythm', label: '운영', icon: 'rhythm' },
  { key: 'memory', label: '기록', icon: 'memory' },
  { key: 'handoff', label: '바통', icon: 'handoff' },
]

const statusCopy = {
  WAITING: '예정',
  DONE: '완료',
} satisfies Record<RoutineStatus, string>

const phaseCopy = {
  BEFORE: '모임 전',
  DURING: '모임 중',
  AFTER: '모임 후',
} satisfies Record<RoutinePhase, string>

const categoryCopy = {
  RESPONSIBILITY: '책임',
  ROUTINE: '루틴',
  RESOURCE: '자료',
  ADVICE: '조언',
} satisfies Record<HandoffCategory, string>

type ModalType = 'decision' | 'role' | 'roleResource' | 'routine' | 'round' | 'handoffItem' | 'handoffPreview' | 'shareLink' | 'accessKey' | null
type Toast = { message: string; tone: 'success' | 'error' }
type CreationModalStatus = {
  pending: boolean
  error: unknown
  storageError: string
  recoveryAvailable: boolean
}
type RoleFormRequest = CreateRoleRequest & UpdateRoleRequest
type RoleResourceFormRequest = CreateRoleResourceRequest & UpdateRoleResourceRequest
type RoutineFormRequest = CreateRoutineRequest & UpdateRoutineRequest

type WorkspaceAppProps = WorkspaceScope & {
  accessDeniedAction?: ReactNode
  onWorkspaceLoaded?: (workspace: WorkspaceProjection) => void
}

function getMember(members: Member[], memberId?: string | null) {
  return members.find((member) => member.id === memberId)
}

function formatLocalDate(value?: string | null) {
  if (!value) return '미정'
  const [year, month, day] = value.split('-').map(Number)
  if (!year || !month || !day) return value
  return `${year}. ${month}. ${day}.`
}

function formatDateRange(startDate?: string | null, endDate?: string | null) {
  if (!startDate && !endDate) return '담당 기간 미정'
  return `${formatLocalDate(startDate)} — ${formatLocalDate(endDate)}`
}

function formatInstant(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('ko-KR', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

function formatSyncTime(value: number) {
  return new Intl.DateTimeFormat('ko-KR', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(new Date(value))
}

function formatToday() {
  return new Intl.DateTimeFormat('ko-KR', {
    month: 'long',
    day: 'numeric',
    weekday: 'long',
  }).format(new Date())
}

function localTodayValue() {
  const today = new Date()
  const year = today.getFullYear()
  const month = String(today.getMonth() + 1).padStart(2, '0')
  const day = String(today.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function clampToSeason(value: string, season: Season) {
  if (value < season.startDate) return season.startDate
  if (value > season.endDate) return season.endDate
  return value
}

function sortedSeasonRounds(rounds: SeasonRound[]) {
  return [...rounds].sort((left, right) => {
    const dateOrder = (left.meetingDate ?? '').localeCompare(right.meetingDate ?? '')
    if (dateOrder !== 0) return dateOrder
    const nameOrder = left.name.localeCompare(right.name, 'ko')
    return nameOrder !== 0 ? nameOrder : left.id.localeCompare(right.id)
  })
}

function localDateNumber(value: string) {
  const [year, month, day] = value.split('-').map(Number)
  if (!year || !month || !day) return 0
  return Date.UTC(year, month - 1, day)
}

function seasonProgress(season: Season) {
  const start = localDateNumber(season.startDate)
  const end = localDateNumber(season.endDate)
  const now = Date.now()
  const duration = Math.max(1, end - start)
  const percent = Math.round(Math.min(1, Math.max(0, (now - start) / duration)) * 100)
  const week = 7 * 24 * 60 * 60 * 1000
  const totalWeeks = Math.max(1, Math.ceil(duration / week))
  const elapsedWeeks = Math.min(totalWeeks, Math.max(0, Math.ceil((now - start) / week)))
  return { percent, totalWeeks, elapsedWeeks }
}

function daysUntil(value: string) {
  const today = new Date()
  const todayNumber = Date.UTC(today.getFullYear(), today.getMonth(), today.getDate())
  return Math.ceil((localDateNumber(value) - todayNumber) / (24 * 60 * 60 * 1000))
}

function mutationError(error: unknown) {
  if (error instanceof ApiError && error.code === 'WORKSPACE_CONTENT_CONFLICT') {
    return '다른 구성원이 먼저 수정했습니다. 최신 내용을 다시 불러왔으니 확인 후 다시 저장해 주세요.'
  }
  if (error instanceof ApiError && error.code === 'IDEMPOTENCY_REPLAY_EXPIRED') {
    return '더 최신 접근 키 변경이 완료되어 이전 결과를 다시 받을 수 없습니다. 새 요청으로 다시 시도해 주세요.'
  }
  return error instanceof Error ? error.message : '요청을 처리하지 못했습니다. 다시 시도해 주세요.'
}

function isExpiredIdempotencyReplay(error: unknown) {
  return error instanceof ApiError && error.code === 'IDEMPOTENCY_REPLAY_EXPIRED'
}

function isWorkspaceAccessDenied(error: unknown) {
  return error instanceof ApiError && error.code === 'WORKSPACE_ACCESS_DENIED'
}

function isWorkspaceContentConflict(error: unknown) {
  return error instanceof ApiError && error.code === 'WORKSPACE_CONTENT_CONFLICT'
}

const terminalContentCreationCodes = new Set([
  'IDEMPOTENCY_KEY_REUSED',
  'IDEMPOTENCY_REPLAY_EXPIRED',
  'INVALID_INPUT',
  'ROLE_NAME_CONFLICT',
  'ROUND_NAME_CONFLICT',
  'TEAM_NOT_FOUND',
  'SEASON_NOT_FOUND',
  'MEMBER_NOT_FOUND',
  'ROLE_NOT_FOUND',
])

function shouldClearPendingContentCreation(error: unknown) {
  return error instanceof ApiError && terminalContentCreationCodes.has(error.code)
}

function contentCreationError(error: unknown) {
  if (error instanceof ApiError
    && (error.code === 'IDEMPOTENCY_KEY_REUSED' || error.code === 'IDEMPOTENCY_REPLAY_EXPIRED')) {
    return '이전 생성 요청을 더 재생할 수 없습니다. 목록에 항목이 이미 생겼는지 확인한 뒤, 필요하면 다시 제출해 주세요.'
  }
  if (shouldClearPendingContentCreation(error)) return mutationError(error)
  return `${mutationError(error)} 입력 내용을 바꾸지 않고 다시 제출하면 같은 요청으로 안전하게 확인합니다.`
}

const pendingStorageRequiredMessage = '요청을 안전하게 저장할 수 없습니다. 시크릿 창이 아닌 일반 브라우저 창에서 열거나 브라우저 저장을 허용한 뒤 다시 시도해 주세요.'
const pendingCreationLimitMessage = '확인되지 않은 생성 요청이 20개 남아 새 요청을 시작할 수 없습니다. 이전에 제출했던 같은 내용을 다시 제출해 결과를 확인한 뒤 시도해 주세요.'

function contentCreationPreparationError(reason: 'storageUnavailable' | 'pendingLimitReached') {
  return reason === 'pendingLimitReached' ? pendingCreationLimitMessage : pendingStorageRequiredMessage
}

function replaceAccessKeyFragment(accessKey?: string) {
  const fragment = accessKey ? `#accessKey=${encodeURIComponent(accessKey)}` : ''
  window.history.replaceState(
    window.history.state,
    '',
    `${window.location.pathname}${window.location.search}${fragment}`,
  )
}

export default function WorkspaceApp({ teamId, seasonId, accessKey, accessDeniedAction, onWorkspaceLoaded }: WorkspaceAppProps) {
  const [currentAccessKey, setCurrentAccessKey] = useState(accessKey)
  const scope = { teamId, seasonId, accessKey: currentAccessKey }
  const workspaceQuery = useWorkspaceQuery(scope)
  const roleMutation = useCreateRoleMutation(scope)
  const updateRoleMutation = useUpdateRoleMutation(scope)
  const roleResourceMutation = useCreateRoleResourceMutation(scope)
  const updateRoleResourceMutation = useUpdateRoleResourceMutation(scope)
  const routineMutation = useCreateRoutineMutation(scope)
  const updateRoutineMutation = useUpdateRoutineMutation(scope)
  const roundMutation = useCreateSeasonRoundMutation(scope)
  const routineExecutionCompletionMutation = useRoutineExecutionCompletionMutation(scope)
  const decisionMutation = useCreateDecisionMutation(scope)
  const handoffItemMutation = useCreateHandoffItemMutation(scope)
  const handoffCompletionMutation = useHandoffCompletionMutation(scope)
  const rotateAccessKeyMutation = useRotateAccessKeyMutation(scope)

  const [view, setView] = useState<ViewKey>('today')
  const [selectedRoleId, setSelectedRoleId] = useState('')
  const [selectedRoundId, setSelectedRoundId] = useState('')
  const [modal, setModal] = useState<ModalType>(null)
  const [editingRole, setEditingRole] = useState<Role | null>(null)
  const [editingRoleResource, setEditingRoleResource] = useState<RoleResource | null>(null)
  const [editingRoutine, setEditingRoutine] = useState<Routine | null>(null)
  const [roleResourceConflictUnresolved, setRoleResourceConflictUnresolved] = useState(false)
  const [inspectorOpen, setInspectorOpen] = useState(false)
  const [toast, setToast] = useState<Toast | null>(null)
  const [rotationStorageError, setRotationStorageError] = useState('')
  const [contentStorageErrors, setContentStorageErrors] = useState<Partial<Record<ContentCreationOperation, string>>>({})
  const pendingRotationIdempotencyKey = pendingAccessKeyRotation(teamId)

  const setContentStorageError = (operation: ContentCreationOperation, message: string) => {
    setContentStorageErrors((current) => ({ ...current, [operation]: message }))
  }

  useEffect(() => {
    if (workspaceQuery.data) onWorkspaceLoaded?.(workspaceQuery.data)
  }, [onWorkspaceLoaded, workspaceQuery.data])

  useEffect(() => {
    const rounds = sortedSeasonRounds(workspaceQuery.data?.rounds ?? [])
    setSelectedRoundId((current) =>
      rounds.some((round) => round.id === current) ? current : rounds.at(-1)?.id ?? '',
    )
  }, [workspaceQuery.data?.rounds])

  const showToast = (message: string, tone: Toast['tone'] = 'success') => {
    setToast({ message, tone })
    window.setTimeout(() => setToast(null), 2800)
  }

  const finishAccessKeyRotation = (rotatedAccessKey: string, idempotencyKey: string) => {
    setCurrentAccessKey(rotatedAccessKey)
    const saved = saveAccessKey(teamId, rotatedAccessKey)
    replaceAccessKeyFragment(saved ? undefined : rotatedAccessKey)
    clearPendingAccessKeyRotation(teamId, idempotencyKey)
    if (saved) {
      setModal(null)
      showToast('접근 키를 바꿨어요. 이제 새 공유 링크만 사용할 수 있습니다.')
    } else {
      setModal('shareLink')
      showToast('새 키를 저장하지 못했습니다. 표시된 링크를 안전한 곳에 보관해 주세요.', 'error')
    }
  }

  const handleAccessKeyRotationError = (error: unknown, idempotencyKey: string, recovering = false) => {
    if (isExpiredIdempotencyReplay(error) || (recovering && isWorkspaceAccessDenied(error))) {
      clearPendingAccessKeyRotation(teamId, idempotencyKey)
    }
  }

  const recoverPendingAccessKeyRotation = () => {
    if (!pendingRotationIdempotencyKey || rotateAccessKeyMutation.isPending) return
    rotateAccessKeyMutation.mutate(pendingRotationIdempotencyKey, {
      onSuccess: ({ accessKey: rotatedAccessKey }) => {
        finishAccessKeyRotation(rotatedAccessKey, pendingRotationIdempotencyKey)
      },
      onError: (error) => handleAccessKeyRotationError(error, pendingRotationIdempotencyKey, true),
    })
  }

  const workspaceAccessDenied = isWorkspaceAccessDenied(workspaceQuery.error)

  if (workspaceQuery.isPending) {
    return <WorkspaceState title="작업 공간을 불러오는 중이에요" description="팀의 바통과 이번 시즌 기록을 모으고 있습니다." busy />
  }

  if (!workspaceQuery.data || workspaceAccessDenied) {
    const isAccessDenied = workspaceAccessDenied
    const pendingRotationRecovery = isAccessDenied && pendingRotationIdempotencyKey
      ? (
          <div className="workspace-key-fallback">
            <p>접근 키 변경은 서버에 반영됐지만 응답을 받지 못했을 수 있습니다.</p>
            {rotateAccessKeyMutation.error && <p className="form-error" role="alert">{mutationError(rotateAccessKeyMutation.error)}</p>}
            <button
              type="button"
              className="primary-button"
              disabled={rotateAccessKeyMutation.isPending}
              onClick={recoverPendingAccessKeyRotation}
            >
              {rotateAccessKeyMutation.isPending ? '변경 결과 확인하는 중…' : '접근 키 변경 완료 확인/복구'}
            </button>
          </div>
        )
      : undefined
    const expiredRotationRecovery = isAccessDenied && isExpiredIdempotencyReplay(rotateAccessKeyMutation.error)
      ? (
          <div className="workspace-key-fallback">
            <p className="form-error" role="alert">더 최신 접근 키 변경이 완료되어 이전 결과를 자동 복구할 수 없습니다.</p>
            <p>작업 공간 운영자에게 새 공유 링크를 요청하거나, 이미 전달받은 최신 링크가 있는지 확인해 주세요.</p>
          </div>
        )
      : undefined
    const supersededRotationRecovery = isAccessDenied && isWorkspaceAccessDenied(rotateAccessKeyMutation.error)
      ? (
          <div className="workspace-key-fallback">
            <p className="form-error" role="alert">다른 기기에서 더 최신 접근 키 변경이 완료된 것으로 보입니다.</p>
            <p>작업 공간 운영자에게 새 공유 링크를 요청하거나, 이미 전달받은 최신 링크가 있는지 확인해 주세요.</p>
          </div>
        )
      : undefined
    return (
      <WorkspaceState
        title="작업 공간을 불러오지 못했어요"
        description={mutationError(workspaceQuery.error)}
        action={expiredRotationRecovery ?? supersededRotationRecovery ?? pendingRotationRecovery ?? (isAccessDenied && accessDeniedAction
          ? accessDeniedAction
          : <button type="button" className="primary-button" onClick={() => workspaceQuery.refetch()}>다시 시도하기</button>)}
      />
    )
  }

  const workspace = workspaceQuery.data
  const { roles, resources, routines, rounds, decisions, handoffItems, members } = workspace
  const orderedRounds = sortedSeasonRounds(rounds)
  const selectedRound = orderedRounds.find((round) => round.id === selectedRoundId)
    ?? orderedRounds.at(-1)
  const selectedRole = roles.find((role) => role.id === selectedRoleId) ?? roles[0]
  const effectiveSelectedRoleId = selectedRole?.id ?? ''
  const pendingCount = selectedRound?.routineExecutions.filter((execution) => execution.status !== 'DONE').length ?? 0
  const completedCount = selectedRound?.routineExecutions.filter((execution) => execution.status === 'DONE').length ?? 0
  const shareUrl = `${window.location.origin}/teams/${encodeURIComponent(teamId)}/seasons/${encodeURIComponent(seasonId)}#accessKey=${encodeURIComponent(currentAccessKey)}`
  const hasPendingRoleCreation = modal === 'role' && !editingRole && hasPendingContentCreation(scope, 'role')
  const hasPendingRoutineCreation = modal === 'routine' && !editingRoutine && hasPendingContentCreation(scope, 'routine')
  const hasPendingRoundCreation = modal === 'round' && hasPendingContentCreation(scope, 'round')
  const hasPendingDecisionCreation = modal === 'decision' && hasPendingContentCreation(scope, 'decision')
  const hasPendingHandoffCreation = modal === 'handoffItem' && hasPendingContentCreation(scope, 'handoffItem')
  const hasPendingRoleResourceCreation = modal === 'roleResource'
    && !editingRoleResource
    && hasPendingContentCreation(scope, 'roleResource')

  const selectRole = (roleId: string, openInspector = true) => {
    setSelectedRoleId(roleId)
    setInspectorOpen(openInspector)
  }

  const openView = (key: ViewKey) => {
    setView(key)
    setInspectorOpen(false)
  }

  const handoffProgress = (roleId: string) => {
    const items = handoffItems.filter((item) => item.roleId === roleId)
    if (!items.length) return 0
    return Math.round((items.filter((item) => item.completed).length / items.length) * 100)
  }

  const openRoleModal = () => {
    setEditingRole(null)
    roleMutation.reset()
    setContentStorageError('role', '')
    setModal('role')
  }

  const openRoutineModal = () => {
    if (!roles.length) {
      setView('roles')
      showToast('루틴을 연결할 역할부터 만들어 주세요.', 'error')
      return
    }
    setEditingRoutine(null)
    routineMutation.reset()
    setContentStorageError('routine', '')
    setModal('routine')
  }

  const openRoundModal = () => {
    if (!routines.length) {
      showToast('회차를 만들기 전에 반복 루틴을 하나 이상 준비해 주세요.', 'error')
      return
    }
    roundMutation.reset()
    setContentStorageError('round', '')
    setModal('round')
  }

  const openRoleEditModal = (role: Role) => {
    updateRoleMutation.reset()
    setEditingRole(role)
    setModal('role')
  }

  const openRoleResourceModal = () => {
    if (!roles.length) {
      setView('roles')
      showToast('자료를 연결할 역할부터 만들어 주세요.', 'error')
      return
    }
    setEditingRoleResource(null)
    roleResourceMutation.reset()
    setContentStorageError('roleResource', '')
    setModal('roleResource')
  }

  const openRoleResourceEditModal = (resource: RoleResource) => {
    if (roleResourceConflictUnresolved) {
      showToast('최신 자료를 확인하고 있습니다. 잠시 뒤 다시 열어 주세요.', 'error')
      void refreshRoleResourcesAfterConflict()
      return
    }
    updateRoleResourceMutation.reset()
    setEditingRoleResource(resource)
    setModal('roleResource')
  }

  const openRoutineEditModal = (routine: Routine) => {
    updateRoutineMutation.reset()
    setEditingRoutine(routine)
    setModal('routine')
  }

  const openDecisionModal = () => {
    if (!roles.length || !members.length) {
      showToast('결정에 연결할 역할과 작성자부터 준비해 주세요.', 'error')
      return
    }
    decisionMutation.reset()
    setContentStorageError('decision', '')
    setModal('decision')
  }

  const openHandoffItemModal = () => {
    if (!roles.length) {
      setView('roles')
      showToast('바통을 남길 역할부터 만들어 주세요.', 'error')
      return
    }
    handoffItemMutation.reset()
    setContentStorageError('handoffItem', '')
    setModal('handoffItem')
  }

  const addRole = (request: RoleFormRequest) => {
    const preparation = prepareContentCreation(scope, 'role', request)
    if (preparation.status === 'blocked') {
      roleMutation.reset()
      setContentStorageError('role', contentCreationPreparationError(preparation.reason))
      return
    }
    const { idempotencyKey } = preparation
    setContentStorageError('role', '')
    roleMutation.mutate({ request, idempotencyKey }, {
      onSuccess: () => {
        clearPendingContentCreation(scope, 'role', request, idempotencyKey)
        setModal(null)
        setView('roles')
        showToast('새 역할을 팀의 책임 지도에 추가했어요.')
      },
      onError: (error) => {
        if (shouldClearPendingContentCreation(error)) {
          clearPendingContentCreation(scope, 'role', request, idempotencyKey)
        }
      },
    })
  }

  const updateExistingRole = (request: RoleFormRequest) => {
    if (!editingRole) return
    const roleId = editingRole.id
    updateRoleMutation.mutate({ id: roleId, request }, {
      onSuccess: () => {
        setSelectedRoleId(roleId)
        setModal(null)
        showToast('역할 정보를 수정했어요.')
      },
    })
  }

  const addRoleResource = (request: RoleResourceFormRequest) => {
    const preparation = prepareContentCreation(scope, 'roleResource', request)
    if (preparation.status === 'blocked') {
      roleResourceMutation.reset()
      setContentStorageError('roleResource', contentCreationPreparationError(preparation.reason))
      return
    }
    const { idempotencyKey } = preparation
    setContentStorageError('roleResource', '')
    roleResourceMutation.mutate({ request, idempotencyKey }, {
      onSuccess: (createdResource) => {
        clearPendingContentCreation(scope, 'roleResource', request, idempotencyKey)
        setSelectedRoleId(createdResource.roleId)
        setModal(null)
        setView('roles')
        showToast('역할에 참고 자료를 연결했어요.')
      },
      onError: (error) => {
        if (shouldClearPendingContentCreation(error)) {
          clearPendingContentCreation(scope, 'roleResource', request, idempotencyKey)
        }
      },
    })
  }

  const refreshRoleResourcesAfterConflict = async () => {
    const refreshed = await workspaceQuery.refetch()
    if (refreshed.isSuccess) {
      setRoleResourceConflictUnresolved(false)
      showToast('다른 구성원의 최신 자료를 불러왔어요. 내용을 확인한 뒤 다시 열어 주세요.', 'error')
      return
    }
    showToast('최신 자료를 불러오지 못했어요. 연결을 확인한 뒤 다시 시도해 주세요.', 'error')
  }

  const updateExistingRoleResource = (request: RoleResourceFormRequest) => {
    if (!editingRoleResource) return
    updateRoleResourceMutation.mutate({ id: editingRoleResource.id, request }, {
      onSuccess: (updatedResource) => {
        setSelectedRoleId(updatedResource.roleId)
        setModal(null)
        setView('roles')
        showToast('자료 링크를 수정했어요.')
      },
      onError: (error) => {
        if (!isWorkspaceContentConflict(error)) return
        setRoleResourceConflictUnresolved(true)
        setEditingRoleResource(null)
        setModal(null)
        void refreshRoleResourcesAfterConflict()
      },
    })
  }

  const addRoutine = (request: RoutineFormRequest) => {
    const preparation = prepareContentCreation(scope, 'routine', request)
    if (preparation.status === 'blocked') {
      routineMutation.reset()
      setContentStorageError('routine', contentCreationPreparationError(preparation.reason))
      return
    }
    const { idempotencyKey } = preparation
    setContentStorageError('routine', '')
    routineMutation.mutate({ request, idempotencyKey }, {
      onSuccess: () => {
        clearPendingContentCreation(scope, 'routine', request, idempotencyKey)
        setModal(null)
        setView('rhythm')
        showToast('반복 루틴을 운영 흐름에 추가했어요.')
      },
      onError: (error) => {
        if (shouldClearPendingContentCreation(error)) {
          clearPendingContentCreation(scope, 'routine', request, idempotencyKey)
        }
      },
    })
  }

  const updateExistingRoutine = (request: RoutineFormRequest) => {
    if (!editingRoutine) return
    updateRoutineMutation.mutate({ id: editingRoutine.id, request }, {
      onSuccess: () => {
        setModal(null)
        setView('rhythm')
        showToast('루틴 정보를 수정했어요.')
      },
    })
  }

  const addSeasonRound = (request: CreateSeasonRoundRequest) => {
    const preparation = prepareContentCreation(scope, 'round', request)
    if (preparation.status === 'blocked') {
      roundMutation.reset()
      setContentStorageError('round', contentCreationPreparationError(preparation.reason))
      return
    }
    const { idempotencyKey } = preparation
    setContentStorageError('round', '')
    roundMutation.mutate({ request, idempotencyKey }, {
      onSuccess: (createdRound) => {
        clearPendingContentCreation(scope, 'round', request, idempotencyKey)
        setSelectedRoundId(createdRound.id)
        setModal(null)
        setView('rhythm')
        showToast(`${createdRound.name} 운영 회차를 만들었어요.`)
      },
      onError: (error) => {
        if (shouldClearPendingContentCreation(error)) {
          clearPendingContentCreation(scope, 'round', request, idempotencyKey)
        }
      },
    })
  }

  const toggleRoutineExecution = (execution: RoutineExecution) => {
    if (!selectedRound || execution.roundId !== selectedRound.id
      || routineExecutionCompletionMutation.isPending) return
    const completed = execution.status !== 'DONE'
    routineExecutionCompletionMutation.mutate(
      { roundId: selectedRound.id, executionId: execution.id, completed },
      {
        onSuccess: () => showToast(completed ? '이번 바통을 넘겼어요.' : '완료 표시를 되돌렸어요.'),
        onError: (error) => showToast(`완료 상태를 바꾸지 못했어요. ${mutationError(error)}`, 'error'),
      },
    )
  }

  const addDecision = (request: CreateDecisionRequest) => {
    const preparation = prepareContentCreation(scope, 'decision', request)
    if (preparation.status === 'blocked') {
      decisionMutation.reset()
      setContentStorageError('decision', contentCreationPreparationError(preparation.reason))
      return
    }
    const { idempotencyKey } = preparation
    setContentStorageError('decision', '')
    decisionMutation.mutate({ request, idempotencyKey }, {
      onSuccess: () => {
        clearPendingContentCreation(scope, 'decision', request, idempotencyKey)
        setModal(null)
        setView('memory')
        showToast('결정과 이유를 팀의 기억에 남겼어요.')
      },
      onError: (error) => {
        if (shouldClearPendingContentCreation(error)) {
          clearPendingContentCreation(scope, 'decision', request, idempotencyKey)
        }
      },
    })
  }

  const addHandoffItem = (request: CreateHandoffItemRequest) => {
    const preparation = prepareContentCreation(scope, 'handoffItem', request)
    if (preparation.status === 'blocked') {
      handoffItemMutation.reset()
      setContentStorageError('handoffItem', contentCreationPreparationError(preparation.reason))
      return
    }
    const { idempotencyKey } = preparation
    setContentStorageError('handoffItem', '')
    handoffItemMutation.mutate({ request, idempotencyKey }, {
      onSuccess: () => {
        clearPendingContentCreation(scope, 'handoffItem', request, idempotencyKey)
        setSelectedRoleId(request.roleId)
        setModal(null)
        setView('handoff')
        showToast('바통북에 새 항목을 추가했어요.')
      },
      onError: (error) => {
        if (shouldClearPendingContentCreation(error)) {
          clearPendingContentCreation(scope, 'handoffItem', request, idempotencyKey)
        }
      },
    })
  }

  const toggleHandoff = (id: string) => {
    const item = handoffItems.find((candidate) => candidate.id === id)
    if (!item || handoffCompletionMutation.isPending) return
    const completed = !item.completed
    handoffCompletionMutation.mutate(
      { id, completed },
      {
        onSuccess: () => showToast(completed ? '바통 항목을 준비했어요.' : '바통 항목을 다시 열었어요.'),
        onError: (error) => showToast(`바통 상태를 바꾸지 못했어요. ${mutationError(error)}`, 'error'),
      },
    )
  }

  const copyShareLink = async () => {
    try {
      if (!navigator.clipboard?.writeText) throw new Error('clipboard unavailable')
      await navigator.clipboard.writeText(shareUrl)
      showToast('공유 링크를 복사했어요.')
    } catch {
      setModal('shareLink')
      showToast('자동 복사가 차단되어 직접 복사할 링크를 열었어요.', 'error')
    }
  }

  const rotateWorkspaceAccessKey = () => {
    const confirmed = window.confirm('접근 키를 바꾸면 지금까지 공유한 링크는 즉시 열리지 않게 됩니다. 새 키로 교체할까요?')
    if (!confirmed) return

    const idempotencyKey = idempotencyKeyForAccessKeyRotation(teamId)
    if (!idempotencyKey) {
      rotateAccessKeyMutation.reset()
      setRotationStorageError(pendingStorageRequiredMessage)
      return
    }
    setRotationStorageError('')
    rotateAccessKeyMutation.mutate(idempotencyKey, {
      onSuccess: ({ accessKey: rotatedAccessKey }) => {
        finishAccessKeyRotation(rotatedAccessKey, idempotencyKey)
      },
      onError: (error) => handleAccessKeyRotationError(error, idempotencyKey),
    })
  }

  return (
    <div className={`app-shell ${selectedRole ? '' : 'no-inspector'}`}>
      <Sidebar workspace={workspace} view={view} onNavigate={openView} onShare={copyShareLink} onManageAccess={() => setModal('accessKey')} />

      <main className="main-surface">
        <MobileTopbar teamName={workspace.team.name} onShare={copyShareLink} onManageAccess={() => setModal('accessKey')} />
        <div className="page-stage" key={view}>
          <WorkspaceSyncStatus
            updatedAt={workspaceQuery.dataUpdatedAt}
            syncing={workspaceQuery.isFetching}
            failed={workspaceQuery.isRefetchError}
            onRefresh={() => { void workspaceQuery.refetch() }}
          />
          {view === 'today' && (
            <TodayView
              workspace={workspace}
              rounds={orderedRounds}
              selectedRound={selectedRound}
              pendingCount={pendingCount}
              completedCount={completedCount}
              onSelectRound={setSelectedRoundId}
              onAddRound={openRoundModal}
              onSelectRole={selectRole}
              onOpenDecision={openDecisionModal}
              onToggleRoutine={toggleRoutineExecution}
              onNavigate={openView}
              onAddRole={openRoleModal}
              onAddRoutine={openRoutineModal}
              onEditRoutine={openRoutineEditModal}
              routineCompletionPending={routineExecutionCompletionMutation.isPending}
            />
          )}
          {view === 'roles' && (
            <RolesView
              roles={roles}
              members={members}
              selectedRoleId={effectiveSelectedRoleId}
              onSelectRole={selectRole}
              onAddRole={openRoleModal}
              onEditRole={openRoleEditModal}
              handoffProgress={handoffProgress}
            />
          )}
          {view === 'rhythm' && (
            <RhythmView
              roles={roles}
              routines={routines}
              rounds={orderedRounds}
              selectedRound={selectedRound}
              members={members}
              onSelectRound={setSelectedRoundId}
              onAddRound={openRoundModal}
              onSelectRole={selectRole}
              onToggleRoutine={toggleRoutineExecution}
              onAddRoutine={openRoutineModal}
              onAddRole={openRoleModal}
              onEditRoutine={openRoutineEditModal}
              completionPending={routineExecutionCompletionMutation.isPending}
            />
          )}
          {view === 'memory' && (
            <MemoryView
              decisions={decisions}
              roles={roles}
              onOpenDecision={openDecisionModal}
              onAddRole={openRoleModal}
              onSelectRole={selectRole}
            />
          )}
          {view === 'handoff' && (
            <HandoffView
              roles={roles}
              members={members}
              season={workspace.season}
              selectedRoleId={effectiveSelectedRoleId}
              handoffItems={handoffItems}
              onSelectRole={(id) => selectRole(id, false)}
              onToggle={toggleHandoff}
              progress={handoffProgress}
              onPreview={() => setModal('handoffPreview')}
              onAddItem={openHandoffItemModal}
              onAddRole={openRoleModal}
              completionPending={handoffCompletionMutation.isPending}
            />
          )}
        </div>
      </main>

      {selectedRole && (
        <RoleInspector
          role={selectedRole}
          members={members}
          decisions={decisions}
          routines={routines}
          resources={resources.filter((resource) => resource.roleId === selectedRole.id)}
          progress={handoffProgress(selectedRole.id)}
          open={inspectorOpen}
          onClose={() => setInspectorOpen(false)}
          onAddResource={openRoleResourceModal}
          onEditResource={openRoleResourceEditModal}
          onOpenHandoff={() => {
            setView('handoff')
            setInspectorOpen(false)
          }}
        />
      )}

      <MobileNav view={view} onNavigate={openView} />

      {modal === 'decision' && (
        <DecisionModal
          roles={roles}
          members={members}
          selectedRoleId={effectiveSelectedRoleId}
          pending={decisionMutation.isPending}
          error={decisionMutation.error}
          storageError={contentStorageErrors.decision ?? ''}
          recoveryAvailable={hasPendingDecisionCreation}
          onClose={() => setModal(null)}
          onSave={addDecision}
        />
      )}
      {modal === 'role' && (
        <RoleModal
          members={members}
          season={workspace.season}
          role={editingRole ?? undefined}
          pending={editingRole ? updateRoleMutation.isPending : roleMutation.isPending}
          error={editingRole ? updateRoleMutation.error : roleMutation.error}
          storageError={editingRole ? '' : contentStorageErrors.role ?? ''}
          recoveryAvailable={editingRole ? false : hasPendingRoleCreation}
          onClose={() => setModal(null)}
          onSave={editingRole ? updateExistingRole : addRole}
        />
      )}
      {modal === 'routine' && (
        <RoutineModal
          roles={roles}
          selectedRoleId={effectiveSelectedRoleId}
          routine={editingRoutine ?? undefined}
          pending={editingRoutine ? updateRoutineMutation.isPending : routineMutation.isPending}
          error={editingRoutine ? updateRoutineMutation.error : routineMutation.error}
          storageError={editingRoutine ? '' : contentStorageErrors.routine ?? ''}
          recoveryAvailable={editingRoutine ? false : hasPendingRoutineCreation}
          onClose={() => setModal(null)}
          onSave={editingRoutine ? updateExistingRoutine : addRoutine}
        />
      )}
      {modal === 'roleResource' && (
        <RoleResourceModal
          roles={roles}
          selectedRoleId={effectiveSelectedRoleId}
          resource={editingRoleResource ?? undefined}
          pending={editingRoleResource
            ? updateRoleResourceMutation.isPending
            : roleResourceMutation.isPending}
          error={editingRoleResource
            ? updateRoleResourceMutation.error
            : roleResourceMutation.error}
          storageError={editingRoleResource ? '' : contentStorageErrors.roleResource ?? ''}
          recoveryAvailable={editingRoleResource ? false : hasPendingRoleResourceCreation}
          onClose={() => setModal(null)}
          onSave={editingRoleResource ? updateExistingRoleResource : addRoleResource}
        />
      )}
      {modal === 'round' && (
        <SeasonRoundModal
          season={workspace.season}
          roundCount={rounds.length}
          pending={roundMutation.isPending}
          error={roundMutation.error}
          storageError={contentStorageErrors.round ?? ''}
          recoveryAvailable={hasPendingRoundCreation}
          onClose={() => setModal(null)}
          onSave={addSeasonRound}
        />
      )}
      {modal === 'handoffItem' && (
        <HandoffItemModal
          roles={roles}
          selectedRoleId={effectiveSelectedRoleId}
          pending={handoffItemMutation.isPending}
          error={handoffItemMutation.error}
          storageError={contentStorageErrors.handoffItem ?? ''}
          recoveryAvailable={hasPendingHandoffCreation}
          onClose={() => setModal(null)}
          onSave={addHandoffItem}
        />
      )}
      {modal === 'handoffPreview' && selectedRole && (
        <HandoffPreview
          role={selectedRole}
          members={members}
          routines={routines.filter((routine) => routine.ownerRoleId === selectedRole.id)}
          decisions={decisions}
          resources={resources.filter((resource) => resource.roleId === selectedRole.id)}
          items={handoffItems.filter((item) => item.roleId === selectedRole.id)}
          progress={handoffProgress(selectedRole.id)}
          onClose={() => setModal(null)}
        />
      )}
      {modal === 'shareLink' && <ShareLinkFallback shareUrl={shareUrl} onClose={() => setModal(null)} />}
      {modal === 'accessKey' && (
        <AccessKeyModal
          pending={rotateAccessKeyMutation.isPending}
          error={rotateAccessKeyMutation.error}
          storageError={rotationStorageError}
          onClose={() => setModal(null)}
          onShare={copyShareLink}
          onRotate={rotateWorkspaceAccessKey}
        />
      )}

      {toast && (
        <div className={`toast ${toast.tone === 'error' ? 'toast-error' : ''}`} role="status">
          <Icon name={toast.tone === 'error' ? 'alert' : 'check'} size={16} />{toast.message}
        </div>
      )}
    </div>
  )
}

function WorkspaceState({ title, description, busy = false, action }: { title: string; description: string; busy?: boolean; action?: ReactNode }) {
  return (
    <main className="remote-state-page">
      <section className="remote-state" aria-live="polite" aria-busy={busy}>
        <div className="brand remote-state-brand"><span className="brand-mark" />BATON</div>
        {busy && <span className="loading-mark" aria-hidden="true" />}
        <h1>{title}</h1>
        <p>{description}</p>
        {action}
      </section>
    </main>
  )
}

function Sidebar({ workspace, view, onNavigate, onShare, onManageAccess }: { workspace: WorkspaceProjection; view: ViewKey; onNavigate: (key: ViewKey) => void; onShare: () => void; onManageAccess: () => void }) {
  const progress = seasonProgress(workspace.season)
  return (
    <aside className="sidebar">
      <div className="brand"><span className="brand-mark" />BATON</div>
      <div className="workspace-label">현재 팀</div>
      <div className="workspace-switcher">
        <span className="workspace-symbol">{workspace.team.name.slice(0, 1)}</span>
        <span><strong>{workspace.team.name}</strong><small>{workspace.season.name}</small></span>
      </div>
      <nav className="side-nav" aria-label="주 메뉴">
        {navItems.map((item) => (
          <button type="button" className={view === item.key ? 'active' : ''} key={item.key} onClick={() => onNavigate(item.key)}>
            <Icon name={item.icon} /><span>{item.label}</span>
            {item.key === 'handoff' && workspace.handoffItems.some((candidate) => !candidate.completed) && <span className="nav-dot" aria-label="확인할 바통 있음" />}
          </button>
        ))}
      </nav>
      <div className="sidebar-bottom">
        <div className="season-mini">
          <div><span>시즌 진행</span><strong>{progress.elapsedWeeks} / {progress.totalWeeks}주</strong></div>
          <div className="mini-progress"><span style={{ width: `${progress.percent}%` }} /></div>
          <small>{formatLocalDate(workspace.season.endDate)} 종료</small>
        </div>
        <div className="profile-row">
          <span className="avatar avatar-dark">{workspace.members.length}</span>
          <span><strong>{workspace.members.length}명 함께</strong><small>{workspace.season.name}</small></span>
          <span className="profile-actions">
            <button type="button" onClick={onShare} title="공유 링크 복사">공유</button>
            <button type="button" onClick={onManageAccess}>키 관리</button>
          </span>
        </div>
      </div>
    </aside>
  )
}

function MobileTopbar({ teamName, onShare, onManageAccess }: { teamName: string; onShare: () => void; onManageAccess: () => void }) {
  return (
    <header className="mobile-topbar">
      <div className="brand"><span className="brand-mark" />BATON</div>
      <span className="mobile-team">{teamName}</span>
      <span className="mobile-workspace-actions">
        <button type="button" className="mobile-share" onClick={onShare}>공유</button>
        <button type="button" className="mobile-share" onClick={onManageAccess}>키 관리</button>
      </span>
    </header>
  )
}

function MobileNav({ view, onNavigate }: { view: ViewKey; onNavigate: (key: ViewKey) => void }) {
  return (
    <nav className="mobile-nav" aria-label="모바일 주 메뉴">
      {navItems.map((item) => (
        <button type="button" className={view === item.key ? 'active' : ''} key={item.key} onClick={() => onNavigate(item.key)}>
          <Icon name={item.icon} size={20} /><span>{item.label}</span>
        </button>
      ))}
    </nav>
  )
}

function WorkspaceSyncStatus({ updatedAt, syncing, failed, onRefresh }: {
  updatedAt: number
  syncing: boolean
  failed: boolean
  onRefresh: () => void
}) {
  const message = failed
    ? '최신 내용을 확인하지 못했어요 · 저장된 내용 표시 중'
    : syncing
      ? '다른 구성원의 변경을 확인하는 중…'
      : `${formatSyncTime(updatedAt)}에 화면 갱신`

  return (
    <div className={`workspace-sync-status ${failed ? 'sync-failed' : ''}`}>
      <span className="sync-dot" aria-hidden="true" />
      <span aria-hidden={failed ? true : undefined}>{message}</span>
      <span className="sync-announcement" aria-live="polite" aria-atomic="true">
        {failed ? message : ''}
      </span>
      <button type="button" disabled={syncing} onClick={onRefresh} aria-label="지금 새로고침">
        {syncing ? '확인 중…' : '새로고침'}
      </button>
    </div>
  )
}

function PageHeader({ eyebrow, title, description, action }: { eyebrow: string; title: string; description: string; action?: ReactNode }) {
  return (
    <header className="page-header">
      <div><span className="eyebrow">{eyebrow}</span><h1>{title}</h1><p>{description}</p></div>
      {action && <div className="page-action">{action}</div>}
    </header>
  )
}

function PrimaryButton({ children, onClick, icon = true, disabled = false }: { children: ReactNode; onClick: () => void; icon?: boolean; disabled?: boolean }) {
  return <button type="button" className="primary-button" onClick={onClick} disabled={disabled}>{icon && <Icon name="plus" size={16} />}{children}</button>
}

function ActionableEmpty({ title, description, actionLabel, onAction }: { title: string; description: string; actionLabel: string; onAction: () => void }) {
  return (
    <div className="empty-state actionable-empty">
      <Icon name="spark" size={28} /><strong>{title}</strong><p>{description}</p>
      <button type="button" className="secondary-button" onClick={onAction}>{actionLabel}</button>
    </div>
  )
}

function RoundControl({ rounds, selectedRound, hasRoutines, onSelect, onCreate }: {
  rounds: SeasonRound[]
  selectedRound?: SeasonRound
  hasRoutines: boolean
  onSelect: (roundId: string) => void
  onCreate: () => void
}) {
  return (
    <section className="round-control" aria-label="회차 전환 도구">
      <label>
        <span>운영 회차</span>
        <select
          aria-label="운영 회차"
          value={selectedRound?.id ?? ''}
          disabled={!rounds.length}
          onChange={(event) => onSelect(event.target.value)}
        >
          {!rounds.length && <option value="">아직 만든 회차가 없습니다</option>}
          {rounds.map((round) => (
            <option key={round.id} value={round.id}>
              {round.name} · {formatLocalDate(round.meetingDate)}
            </option>
          ))}
        </select>
      </label>
      <button
        type="button"
        className="secondary-button"
        disabled={!hasRoutines}
        aria-describedby={!hasRoutines ? 'round-create-hint' : undefined}
        onClick={onCreate}
      >
        <Icon name="plus" size={15} /> 회차 만들기
      </button>
      {!hasRoutines && (
        <p id="round-create-hint">반복 루틴을 하나 이상 만든 뒤 운영 회차를 만들 수 있어요.</p>
      )}
    </section>
  )
}

function TodayView({ workspace, rounds, selectedRound, pendingCount, completedCount, onSelectRound, onAddRound, onSelectRole, onOpenDecision, onToggleRoutine, onNavigate, onAddRole, onAddRoutine, onEditRoutine, routineCompletionPending }: {
  workspace: WorkspaceProjection
  rounds: SeasonRound[]
  selectedRound?: SeasonRound
  pendingCount: number
  completedCount: number
  onSelectRound: (roundId: string) => void
  onAddRound: () => void
  onSelectRole: (id: string) => void
  onOpenDecision: () => void
  onToggleRoutine: (execution: RoutineExecution) => void
  onNavigate: (key: ViewKey) => void
  onAddRole: () => void
  onAddRoutine: () => void
  onEditRoutine: (routine: Routine) => void
  routineCompletionPending: boolean
}) {
  const { roles, routines, decisions, members, season } = workspace
  return (
    <>
      <PageHeader
        eyebrow={`${formatToday()} · ${season.name}`}
        title={`${pendingCount}개의 바통이 남았어요`}
        description="이번 운영에서 멈춘 흐름과 다음 담당자를 확인하세요."
        action={<PrimaryButton onClick={onOpenDecision} disabled={!roles.length || !members.length}>결정 남기기</PrimaryButton>}
      />
      <RoundControl
        rounds={rounds}
        selectedRound={selectedRound}
        hasRoutines={Boolean(routines.length)}
        onSelect={onSelectRound}
        onCreate={onAddRound}
      />
      <section className="relay-board" aria-labelledby="relay-title">
        <div className="section-heading">
          <div><span className="section-kicker">이번 운영</span><h2 id="relay-title">바통 라인</h2></div>
          <div className="round-meta">
            <strong>{completedCount}/{selectedRound?.routineExecutions.length ?? 0}</strong>
            <span>{selectedRound ? `완료 · ${selectedRound.name}` : `회차 준비 · ${season.name}`}</span>
          </div>
        </div>
        {!routines.length ? (
          <ActionableEmpty title="아직 운영 루틴이 없어요" description="첫 반복 업무를 역할과 연결해 보세요." actionLabel={roles.length ? '첫 루틴 만들기' : '첫 역할 만들기'} onAction={roles.length ? onAddRoutine : onAddRole} />
        ) : selectedRound ? (
          <div className="relay-line" role="list">
            {routines.map((routine, index) => {
              const execution = selectedRound.routineExecutions.find((item) => item.routineId === routine.id)
              const displayRoutine = execution ?? routine
              const role = roles.find((item) => item.id === displayRoutine.ownerRoleId)
              const member = getMember(members, role?.currentMemberId)
              return (
                <button type="button" className={`relay-step ${execution?.status.toLowerCase() ?? 'future'}`} key={routine.id} onClick={() => role && onSelectRole(role.id)} role="listitem">
                  <span className="relay-index">{String(index + 1).padStart(2, '0')}</span><span className="relay-node"><span /></span>
                  <span className="relay-status">{execution ? statusCopy[execution.status] : '다음 회차부터'}</span><strong>{displayRoutine.title}</strong>
                  <small>{member?.name ?? '담당자 미정'} · {displayRoutine.dueLabel}</small>
                </button>
              )
            })}
          </div>
        ) : (
          <ActionableEmpty title="아직 운영 회차가 없어요" description="준비한 루틴을 이번 운영의 실행 목록으로 복사해 보세요." actionLabel="첫 회차 만들기" onAction={onAddRound} />
        )}
      </section>
      <div className="today-lower">
        <section className="plain-section">
          <div className="section-heading compact"><div><span className="section-kicker">주의가 필요한 곳</span><h2>멈춘 바통</h2></div><button type="button" className="text-button" onClick={() => onNavigate('roles')}>역할에서 보기 <Icon name="arrow" size={14} /></button></div>
          <div className="signal-list">
            {roles.some((role) => role.risk) ? roles.filter((role) => role.risk).slice(0, 3).map((role, index) => (
              <button type="button" className="signal-row" key={role.id} onClick={() => onSelectRole(role.id)}>
                <span className={`signal-symbol ${index === 0 ? 'urgent' : ''}`}><Icon name="alert" size={15} /></span>
                <span><strong>{role.name}</strong><small>{role.risk}</small></span><Icon name="chevron" size={16} />
              </button>
            )) : <p className="quiet-state">현재 등록된 위험 신호가 없어요.</p>}
          </div>
        </section>
        <section className="plain-section decision-glimpse">
          <div className="section-heading compact"><div><span className="section-kicker">최근 변경</span><h2>결정 기록</h2></div><button type="button" className="text-button" onClick={() => onNavigate('memory')}>전체 기록 <Icon name="arrow" size={14} /></button></div>
          {decisions[0] ? (
            <button type="button" className="decision-preview" onClick={() => onNavigate('memory')}>
              <time>{formatInstant(decisions[0].createdAt)}</time><blockquote>“{decisions[0].title}”</blockquote><p>{decisions[0].reason}</p><span>{decisions[0].authorName} 기록</span>
            </button>
          ) : <p className="quiet-state">아직 남긴 결정이 없어요.</p>}
        </section>
      </div>
      {routines.length > 0 && (
        <section className="mobile-this-week plain-section">
          <div className="section-heading compact"><div><span className="section-kicker">이번 운영</span><h2>{selectedRound?.name ?? '다음 회차 준비'}</h2></div></div>
          {routines.map((routine) => {
            const execution = selectedRound?.routineExecutions.find((item) => item.routineId === routine.id)
            const ownerRoleId = execution?.ownerRoleId ?? routine.ownerRoleId
            return (
              <RoutineRow
                key={routine.id}
                routine={routine}
                execution={execution}
                role={roles.find((item) => item.id === ownerRoleId)}
                members={members}
                onToggle={onToggleRoutine}
                onSelectRole={onSelectRole}
                onEdit={onEditRoutine}
                pending={routineCompletionPending}
              />
            )
          })}
        </section>
      )}
    </>
  )
}

function RolesView({ roles, members, selectedRoleId, onSelectRole, onAddRole, onEditRole, handoffProgress }: { roles: Role[]; members: Member[]; selectedRoleId: string; onSelectRole: (id: string) => void; onAddRole: () => void; onEditRole: (role: Role) => void; handoffProgress: (id: string) => number }) {
  return (
    <>
      <PageHeader eyebrow="팀의 책임 지도" title="사람이 바뀌어도 역할은 남아요" description="현재 담당자와 다음 담당자, 반복되는 책임을 한눈에 확인하세요." action={<PrimaryButton onClick={onAddRole}>역할 추가</PrimaryButton>} />
      {roles.length ? (
        <section className="role-directory">
          <div className="directory-head"><span>역할과 목적</span><span>현재 담당자</span><span>다음 담당자</span><span>바통 준비</span></div>
          {roles.map((role) => {
            const owner = getMember(members, role.currentMemberId)
            const next = getMember(members, role.nextMemberId)
            return (
              <div className={`role-row ${selectedRoleId === role.id ? 'selected' : ''}`} key={role.id}>
                <button type="button" className="role-row-open" onClick={() => onSelectRole(role.id)}>
                  <span className="role-main"><span className="role-glyph"><Icon name="roles" size={17} /></span><span><strong>{role.name}</strong><small>{role.purpose}</small></span></span>
                  <span className="person-cell">{owner ? <><span className="avatar" style={{ background: owner.tone }}>{owner.initials}</span><span><strong>{owner.name}</strong><small>{formatDateRange(role.assignmentStartDate, role.assignmentEndDate)}</small></span></> : <em>담당자 미정</em>}</span>
                  <span className="next-cell">{next ? <><span className="avatar" style={{ background: next.tone }}>{next.initials}</span>{next.name}</> : <em>아직 미정</em>}</span>
                  <span className="progress-cell"><strong>{handoffProgress(role.id)}%</strong><span className="thin-progress"><i style={{ width: `${handoffProgress(role.id)}%` }} /></span><Icon name="chevron" size={16} /></span>
                </button>
                <button type="button" className="inline-edit-button" aria-label={`${role.name} 역할 수정`} onClick={() => onEditRole(role)}>수정</button>
              </div>
            )
          })}
        </section>
      ) : <ActionableEmpty title="아직 역할이 없어요" description="사람보다 오래 남을 첫 책임을 역할로 만들어 보세요." actionLabel="첫 역할 만들기" onAction={onAddRole} />}
      <p className="directory-note"><Icon name="spark" size={15} /> 사람을 먼저 초대하기보다, 팀에 꼭 필요한 책임부터 역할로 정리해 보세요.</p>
    </>
  )
}

function RhythmView({ roles, routines, rounds, selectedRound, members, onSelectRound, onAddRound, onSelectRole, onToggleRoutine, onAddRoutine, onAddRole, onEditRoutine, completionPending }: { roles: Role[]; routines: Routine[]; rounds: SeasonRound[]; selectedRound?: SeasonRound; members: Member[]; onSelectRound: (roundId: string) => void; onAddRound: () => void; onSelectRole: (id: string) => void; onToggleRoutine: (execution: RoutineExecution) => void; onAddRoutine: () => void; onAddRole: () => void; onEditRoutine: (routine: Routine) => void; completionPending: boolean }) {
  const phases: RoutinePhase[] = ['BEFORE', 'DURING', 'AFTER']
  return (
    <>
      <PageHeader eyebrow="반복되는 운영 리듬" title="우리 팀은 이렇게 움직여요" description="매번 설명하던 일을 루틴으로 만들고, 완료되면 다음 역할로 넘깁니다." action={<PrimaryButton onClick={onAddRoutine}>루틴 추가</PrimaryButton>} />
      <RoundControl rounds={rounds} selectedRound={selectedRound} hasRoutines={Boolean(routines.length)} onSelect={onSelectRound} onCreate={onAddRound} />
      {routines.length ? (
        <div className="rhythm-timeline">
          {phases.map((phase, phaseIndex) => (
            <section className="rhythm-phase" key={phase}>
              <div className="phase-marker"><span>{String(phaseIndex + 1).padStart(2, '0')}</span><h2>{phaseCopy[phase]}</h2></div>
              <div className="phase-content">
                {routines.filter((routine) => {
                  const execution = selectedRound?.routineExecutions.find((item) => item.routineId === routine.id)
                  return (execution?.phase ?? routine.phase) === phase
                }).map((routine) => {
                  const execution = selectedRound?.routineExecutions.find((item) => item.routineId === routine.id)
                  const ownerRoleId = execution?.ownerRoleId ?? routine.ownerRoleId
                  return (
                    <RoutineRow
                      key={routine.id}
                      routine={routine}
                      execution={execution}
                      role={roles.find((role) => role.id === ownerRoleId)}
                      members={members}
                      onToggle={onToggleRoutine}
                      onSelectRole={onSelectRole}
                      onEdit={onEditRoutine}
                      pending={completionPending}
                    />
                  )
                })}
              </div>
            </section>
          ))}
        </div>
      ) : <ActionableEmpty title="아직 반복 루틴이 없어요" description="모임 전·중·후에 반복할 일을 역할과 연결해 주세요." actionLabel={roles.length ? '첫 루틴 만들기' : '첫 역할 만들기'} onAction={roles.length ? onAddRoutine : onAddRole} />}
      {routines.length > 0 && <button type="button" className="add-routine-line" onClick={onAddRoutine}><Icon name="plus" size={15} /> 반복할 일 추가하기</button>}
    </>
  )
}

function RoutineRow({ routine, execution, role, members, onToggle, onSelectRole, onEdit, pending }: { routine: Routine; execution?: RoutineExecution; role?: Role; members: Member[]; onToggle: (execution: RoutineExecution) => void; onSelectRole: (id: string) => void; onEdit: (routine: Routine) => void; pending: boolean }) {
  const displayRoutine = execution ?? routine
  const member = getMember(members, role?.currentMemberId)
  return (
    <div className={`routine-row ${execution?.status.toLowerCase() ?? 'future'}`}>
      {execution ? (
        <button type="button" className="check-button" disabled={pending} onClick={() => onToggle(execution)} aria-label={`${displayRoutine.title} ${execution.status === 'DONE' ? '완료 취소' : '완료 처리'}`} aria-busy={pending}>
          {execution.status === 'DONE' && <Icon name="check" size={14} />}
        </button>
      ) : <span className="check-button check-button-unavailable" aria-hidden="true" />}
      <button type="button" className="routine-copy" onClick={() => role && onSelectRole(role.id)}><span><strong>{displayRoutine.title}</strong><small>{displayRoutine.detail}</small>{!execution && <small className="routine-round-note">다음 회차부터</small>}</span><time>{displayRoutine.dueLabel}</time></button>
      <button type="button" className="routine-owner" onClick={() => role && onSelectRole(role.id)}>{member && <span className="avatar" style={{ background: member.tone }}>{member.initials}</span>}<span><strong>{role?.name ?? '연결된 역할 없음'}</strong><small>{member?.name ?? '담당자 미정'}</small></span></button>
      <button type="button" className="inline-edit-button" aria-label={`${routine.title} 루틴 수정`} onClick={() => onEdit(routine)}>수정</button>
    </div>
  )
}

function MemoryView({ decisions, roles, onOpenDecision, onAddRole, onSelectRole }: { decisions: Decision[]; roles: Role[]; onOpenDecision: () => void; onAddRole: () => void; onSelectRole: (id: string) => void }) {
  return (
    <>
      <PageHeader eyebrow="팀의 결정 원장" title="결과뿐 아니라 이유도 남겨두세요" description="채팅에서 사라질 결정을 다음 시즌도 이해할 수 있는 기록으로 바꿉니다." action={<PrimaryButton onClick={onOpenDecision} disabled={!roles.length}>결정 남기기</PrimaryButton>} />
      {decisions.length ? (
        <section className="memory-ledger">
          <div className="memory-rule"><span>최근 결정</span><span>{decisions.length}개의 기록</span></div>
          {decisions.map((decision, index) => (
            <article className="decision-entry" key={decision.id}>
              <div className="decision-number">{String(decisions.length - index).padStart(2, '0')}</div>
              <div className="decision-body">
                <div className="decision-meta"><time>{formatInstant(decision.createdAt)}</time><span>{decision.authorName}</span></div><h2>{decision.title}</h2>
                <div className="decision-reason"><span>이유</span><p>{decision.reason}</p></div><div className="decision-alternative"><span>검토한 다른 선택</span><p>{decision.alternative}</p></div>
                <div className="decision-tags">{decision.roleIds.map((roleId) => { const role = roles.find((item) => item.id === roleId); return role ? <button type="button" key={roleId} onClick={() => onSelectRole(roleId)}>{role.name}</button> : null })}</div>
              </div>
            </article>
          ))}
        </section>
      ) : <ActionableEmpty title="아직 결정 기록이 없어요" description="운영 방식이 바뀌는 순간, 결과와 이유를 함께 남겨 보세요." actionLabel={roles.length ? '첫 결정 남기기' : '첫 역할 만들기'} onAction={roles.length ? onOpenDecision : onAddRole} />}
    </>
  )
}

function HandoffView({ roles, members, season, selectedRoleId, handoffItems, onSelectRole, onToggle, progress, onPreview, onAddItem, onAddRole, completionPending }: { roles: Role[]; members: Member[]; season: Season; selectedRoleId: string; handoffItems: HandoffItem[]; onSelectRole: (id: string) => void; onToggle: (id: string) => void; progress: (id: string) => number; onPreview: () => void; onAddItem: () => void; onAddRole: () => void; completionPending: boolean }) {
  const selected = roles.find((role) => role.id === selectedRoleId) ?? roles[0]
  if (!selected) {
    return <><PageHeader eyebrow="역할 인수인계" title="첫 역할부터 만들어 주세요" description="역할이 생기면 책임과 운영 맥락을 바통북으로 정리할 수 있습니다." /><ActionableEmpty title="넘겨줄 역할이 아직 없어요" description="팀의 첫 책임을 역할로 추가해 주세요." actionLabel="첫 역할 만들기" onAction={onAddRole} /></>
  }
  const items = handoffItems.filter((item) => item.roleId === selected.id)
  const next = getMember(members, selected.nextMemberId)
  const remainingDays = daysUntil(season.endDate)
  return (
    <>
      <PageHeader
        eyebrow={remainingDays >= 0 ? `시즌 종료까지 ${remainingDays}일` : `${formatLocalDate(season.endDate)} 시즌 종료`}
        title="다음 사람이 헤매지 않도록"
        description="역할의 책임과 맥락을 바통북으로 정리해 다음 담당자에게 넘깁니다."
        action={<div className="action-cluster"><button type="button" className="secondary-button" onClick={onAddItem}><Icon name="plus" size={15} /> 항목 추가</button><PrimaryButton onClick={onPreview} icon={false}>바통북 미리보기</PrimaryButton></div>}
      />
      <div className="handoff-role-tabs" role="tablist" aria-label="역할별 바통">{roles.map((role) => <button type="button" role="tab" aria-selected={selected.id === role.id} className={selected.id === role.id ? 'active' : ''} key={role.id} onClick={() => onSelectRole(role.id)}><span>{role.name}</span><strong>{progress(role.id)}%</strong></button>)}</div>
      <section className="handoff-workspace">
        <div className="handoff-summary"><span className="section-kicker">{selected.name}</span><h2>{next ? `${next.name}님에게 넘길 바통` : '다음 담당자를 기다리는 바통'}</h2><p>{selected.purpose}</p><div className="handoff-score"><strong>{progress(selected.id)}%</strong><span><i style={{ width: `${progress(selected.id)}%` }} /></span><small>{items.filter((item) => item.completed).length}/{items.length} 항목 준비됨</small></div></div>
        <div className="handoff-checklist">
          {items.length ? items.map((item) => (
            <label className={item.completed ? 'done' : ''} key={item.id}>
              <input type="checkbox" checked={item.completed} disabled={completionPending} onChange={() => onToggle(item.id)} /><span className="custom-check">{item.completed && <Icon name="check" size={14} />}</span><span><strong>{item.label}</strong><small>{categoryCopy[item.category]}</small></span>
            </label>
          )) : <ActionableEmpty title="아직 바통북 항목이 없어요" description="다음 담당자가 알아야 할 책임, 자료와 조언을 추가해 주세요." actionLabel="첫 항목 추가하기" onAction={onAddItem} />}
        </div>
      </section>
    </>
  )
}

function RoleInspector({ role, members, decisions, routines, resources, progress, open, onClose, onOpenHandoff, onAddResource, onEditResource }: { role: Role; members: Member[]; decisions: Decision[]; routines: Routine[]; resources: RoleResource[]; progress: number; open: boolean; onClose: () => void; onOpenHandoff: () => void; onAddResource: () => void; onEditResource: (resource: RoleResource) => void }) {
  const owner = getMember(members, role.currentMemberId)
  const next = getMember(members, role.nextMemberId)
  const relatedRoutine = routines.find((routine) => routine.ownerRoleId === role.id)
  const relatedDecision = decisions.find((decision) => decision.roleIds.includes(role.id))
  return (
    <aside className={`inspector ${open ? 'is-open' : ''}`} aria-label="선택한 역할 상세">
      <button type="button" className="inspector-close" onClick={onClose} aria-label="상세 닫기"><Icon name="close" /></button><div className="inspector-topline"><span>선택한 역할</span><span className="live-dot">운영 중</span></div><h2>{role.name}</h2><p className="inspector-purpose">{role.purpose}</p>
      <div className="owner-block"><span className="block-label">현재 담당자</span>{owner ? <div><span className="avatar avatar-large" style={{ background: owner.tone }}>{owner.initials}</span><span><strong>{owner.name}</strong><small>{formatDateRange(role.assignmentStartDate, role.assignmentEndDate)}</small></span></div> : <p className="muted-copy">현재 담당자가 정해지지 않았어요.</p>}</div>
      {role.risk && <div className="risk-note"><Icon name="alert" size={17} /><span><strong>기억이 끊길 수 있어요</strong>{role.risk}</span></div>}
      <div className="inspector-section"><span className="block-label">핵심 책임</span><ul>{role.responsibilities.length ? role.responsibilities.map((item) => <li key={item}><Icon name="check" size={13} />{item}</li>) : <li className="muted">아직 정리된 책임이 없어요.</li>}</ul></div>
      <div className="inspector-section resource-section">
        <div className="resource-section-heading"><span className="block-label">참고 자료</span><button type="button" onClick={onAddResource}><Icon name="plus" size={13} /> 자료 추가</button></div>
        {resources.length ? (
          <ul className="resource-links">
            {resources.map((resource) => (
              <li key={resource.id}>
                <span>
                  <a href={resource.url} target="_blank" rel="noopener noreferrer" aria-label={`${resource.title} 새 창에서 열기`}>{resource.title}</a>
                  {resource.description && <small>{resource.description}</small>}
                </span>
                <button type="button" aria-label={`${resource.title} 자료 수정`} onClick={() => onEditResource(resource)}>수정</button>
              </li>
            ))}
          </ul>
        ) : <p className="muted-copy resource-empty">연결된 자료가 아직 없어요.</p>}
      </div>
      {relatedRoutine && <div className="inspector-section next-event"><span className="block-label">다음 루틴</span><strong>{relatedRoutine.title}</strong><small>{relatedRoutine.dueLabel} · {relatedRoutine.detail}</small></div>}
      {relatedDecision && <div className="inspector-section linked-decision"><span className="block-label">연결된 결정</span><p>“{relatedDecision.title}”</p><small>{formatInstant(relatedDecision.createdAt)}</small></div>}
      <div className="inspector-handoff"><div><span className="block-label">바통 준비도</span><strong>{progress}%</strong></div><div className="thin-progress"><i style={{ width: `${progress}%` }} /></div><p>{next ? `다음 담당자 · ${next.name}` : '다음 담당자가 아직 정해지지 않았어요.'}</p><button type="button" onClick={onOpenHandoff}>바통 정리하기 <Icon name="arrow" size={15} /></button></div>
    </aside>
  )
}

function ModalShell({ title, description, closeDisabled = false, onClose, children }: { title: string; description: string; closeDisabled?: boolean; onClose: () => void; children: ReactNode }) {
  return (
    <div className="modal-backdrop" role="presentation" onMouseDown={(event) => !closeDisabled && event.currentTarget === event.target && onClose()}>
      <section className="modal" role="dialog" aria-modal="true" aria-busy={closeDisabled || undefined} aria-labelledby="modal-title" aria-describedby="modal-description">
        <button type="button" className="modal-close" onClick={onClose} aria-label="닫기" disabled={closeDisabled}><Icon name="close" /></button><span className="section-kicker">BATON</span><h2 id="modal-title">{title}</h2><p id="modal-description" className="modal-description">{description}</p>{children}
      </section>
    </div>
  )
}

function FormError({ error }: { error: unknown }) {
  return error ? <p className="form-error" role="alert">{mutationError(error)}</p> : null
}

function CreationFormFeedback({ error, storageError, recoveryAvailable }: Pick<CreationModalStatus, 'error' | 'storageError' | 'recoveryAvailable'>) {
  if (storageError) return <p className="form-error" role="alert">{storageError}</p>
  if (error) return <p className="form-error" role="alert">{contentCreationError(error)}</p>
  if (recoveryAvailable) {
    return (
      <p className="form-retry-notice" role="status">
        이전에 저장 결과를 확인하지 못한 요청이 있습니다. 그때와 같은 내용을 다시 제출하면 새 항목을 만들지 않고 결과를 확인합니다.
      </p>
    )
  }
  return null
}

function ShareLinkFallback({ shareUrl, onClose }: { shareUrl: string; onClose: () => void }) {
  return (
    <ModalShell title="공유 링크 직접 복사" description="브라우저가 자동 복사를 허용하지 않았어요. 아래 링크를 선택해 복사한 뒤 구성원에게 전달해 주세요." onClose={onClose}>
      <div className="share-link-fallback">
        <label htmlFor="share-link-value">공유 링크</label>
        <input
          id="share-link-value"
          autoFocus
          readOnly
          value={shareUrl}
          onFocus={(event) => event.currentTarget.select()}
          onClick={(event) => event.currentTarget.select()}
        />
        <p>이 링크를 가진 사람은 작업 공간을 읽고 수정할 수 있어요.</p>
        <button type="button" className="primary-button full-button" onClick={onClose}>확인</button>
      </div>
    </ModalShell>
  )
}

function AccessKeyModal({ pending, error, storageError, onClose, onShare, onRotate }: {
  pending: boolean
  error: unknown
  storageError: string
  onClose: () => void
  onShare: () => void
  onRotate: () => void
}) {
  return (
    <ModalShell
      title="공유 접근 키 관리"
      description="공유 링크를 전달하거나, 링크가 외부에 알려졌을 때 접근 키를 새로 발급할 수 있습니다."
      onClose={onClose}
    >
      <div className="access-key-management">
        <div className="access-key-notice">
          <Icon name="alert" size={18} />
          <p><strong>키를 바꾸면 이전 공유 링크는 즉시 열리지 않습니다.</strong>구성원에게 새 공유 링크를 다시 전달해 주세요.</p>
        </div>
        {storageError && <p className="form-error" role="alert">{storageError}</p>}
        <FormError error={error} />
        <div className="form-actions">
          <button type="button" className="secondary-button" onClick={onShare} disabled={pending}>현재 링크 복사</button>
          <button type="button" className="danger-button" onClick={onRotate} disabled={pending}>
            {pending ? '접근 키 바꾸는 중…' : '접근 키 바꾸기'}
          </button>
        </div>
      </div>
    </ModalShell>
  )
}

function DecisionModal({ roles, members, selectedRoleId, pending, error, storageError, recoveryAvailable, onClose, onSave }: CreationModalStatus & { roles: Role[]; members: Member[]; selectedRoleId: string; onClose: () => void; onSave: (decision: CreateDecisionRequest) => void }) {
  const [title, setTitle] = useState('')
  const [reason, setReason] = useState('')
  const [alternative, setAlternative] = useState('')
  const [roleId, setRoleId] = useState(selectedRoleId || roles[0]?.id || '')
  const [authorMemberId, setAuthorMemberId] = useState(members[0]?.id ?? '')
  const submit = (event: FormEvent) => { event.preventDefault(); if (!title.trim() || !reason.trim() || !roleId || !authorMemberId || pending) return; onSave({ title: title.trim(), reason: reason.trim(), alternative: alternative.trim() || '별도 대안을 검토하지 않음', authorMemberId, roleIds: [roleId] }) }
  return (
    <ModalShell title="결정과 이유 남기기" description="나중에 ‘왜 이렇게 했지?’라는 질문에 답할 수 있도록 맥락을 함께 적어주세요." onClose={onClose}>
      <form className="modal-form" onSubmit={submit}>
        <label><span>무엇을 바꾸기로 했나요?</span><input autoFocus required value={title} onChange={(event) => setTitle(event.target.value)} placeholder="예: 세션 시작 시간을 30분 앞당긴다" /></label>
        <label><span>왜 이 선택을 했나요?</span><textarea required value={reason} onChange={(event) => setReason(event.target.value)} placeholder="반복된 문제나 관찰한 근거를 적어주세요" rows={3} /></label>
        <label><span>검토한 다른 선택</span><input value={alternative} onChange={(event) => setAlternative(event.target.value)} placeholder="예: 세션 시간을 30분 연장하기" /></label>
        <label><span>작성자</span><select required value={authorMemberId} onChange={(event) => setAuthorMemberId(event.target.value)}>{members.map((member) => <option key={member.id} value={member.id}>{member.name}</option>)}</select></label>
        <label><span>영향받는 역할</span><select required value={roleId} onChange={(event) => setRoleId(event.target.value)}>{roles.map((role) => <option key={role.id} value={role.id}>{role.name}</option>)}</select></label>
        <CreationFormFeedback error={error} storageError={storageError} recoveryAvailable={recoveryAvailable} /><FormActions pending={pending} submitLabel="결정 기록하기" pendingLabel="결정 기록하는 중…" onClose={onClose} />
      </form>
    </ModalShell>
  )
}

function RoleModal({ members, season, role, pending, error, storageError, recoveryAvailable, onClose, onSave }: CreationModalStatus & { members: Member[]; season: Season; role?: Role; onClose: () => void; onSave: (request: RoleFormRequest) => void }) {
  const editing = Boolean(role)
  const [name, setName] = useState(role?.name ?? '')
  const [purpose, setPurpose] = useState(role?.purpose ?? '')
  const [currentMemberId, setCurrentMemberId] = useState(role?.currentMemberId ?? '')
  const [nextMemberId, setNextMemberId] = useState(role?.nextMemberId ?? '')
  const [assignmentStartDate, setAssignmentStartDate] = useState(role ? role.assignmentStartDate ?? '' : season.startDate)
  const [assignmentEndDate, setAssignmentEndDate] = useState(role ? role.assignmentEndDate ?? '' : season.endDate)
  const [responsibilities, setResponsibilities] = useState(role?.responsibilities.join('\n') ?? '')
  const [risk, setRisk] = useState(role?.risk ?? '')
  const [validationMessage, setValidationMessage] = useState('')
  const submit = (event: FormEvent) => {
    event.preventDefault(); if (pending) return; setValidationMessage('')
    if (assignmentStartDate && assignmentEndDate && assignmentEndDate < assignmentStartDate) { setValidationMessage('담당 종료일은 시작일보다 빠를 수 없습니다.'); return }
    onSave({ name: name.trim(), purpose: purpose.trim(), currentMemberId: currentMemberId || null, nextMemberId: nextMemberId || null, assignmentStartDate: assignmentStartDate || null, assignmentEndDate: assignmentEndDate || null, responsibilities: splitList(responsibilities), risk: risk.trim() || null })
  }
  return (
    <ModalShell title={editing ? '역할 수정' : '새 역할 만들기'} description={editing ? '담당자와 기간, 책임처럼 달라진 역할 정보를 현재 운영에 맞게 고쳐주세요.' : '사람의 직함보다, 팀에 계속 남아야 할 책임과 담당 기간을 정리해 주세요.'} closeDisabled={editing && pending} onClose={onClose}>
      <form className="modal-form" onSubmit={submit}>
        <label><span>역할 이름</span><input autoFocus required value={name} onChange={(event) => setName(event.target.value)} placeholder="예: 질문 큐레이터" /></label>
        <label><span>이 역할이 존재하는 이유</span><textarea required value={purpose} onChange={(event) => setPurpose(event.target.value)} placeholder="이 역할이 팀에서 해결하는 문제를 적어주세요" rows={3} /></label>
        <div className="form-grid"><label><span>현재 담당자</span><select value={currentMemberId} onChange={(event) => setCurrentMemberId(event.target.value)}><option value="">담당자 미정</option>{members.map((member) => <option key={member.id} value={member.id}>{member.name}</option>)}</select></label><label><span>다음 담당자</span><select value={nextMemberId} onChange={(event) => setNextMemberId(event.target.value)}><option value="">다음 담당자 미정</option>{members.map((member) => <option key={member.id} value={member.id}>{member.name}</option>)}</select></label></div>
        <div className="form-grid"><label><span>담당 시작일</span><input type="date" value={assignmentStartDate} onChange={(event) => setAssignmentStartDate(event.target.value)} /></label><label><span>담당 종료일</span><input type="date" min={assignmentStartDate || undefined} value={assignmentEndDate} onChange={(event) => setAssignmentEndDate(event.target.value)} /></label></div>
        <label><span>핵심 책임</span><textarea value={responsibilities} onChange={(event) => setResponsibilities(event.target.value)} placeholder={'질문 수집\n공통 막힘 정리'} rows={3} /><small>줄바꿈 또는 쉼표로 구분해 주세요.</small></label>
        <label><span>위험 신호</span><textarea value={risk} onChange={(event) => setRisk(event.target.value)} placeholder="예: 자료가 개인 계정에만 저장되어 있어요" rows={2} /></label>
        {validationMessage && <p className="form-error" role="alert">{validationMessage}</p>}
        {editing ? <FormError error={error} /> : <CreationFormFeedback error={error} storageError={storageError} recoveryAvailable={recoveryAvailable} />}
        <FormActions pending={pending} submitLabel={editing ? '변경 저장' : '역할 만들기'} pendingLabel={editing ? '역할 저장하는 중…' : '역할 만드는 중…'} onClose={onClose} />
      </form>
    </ModalShell>
  )
}

function RoleResourceModal({ roles, selectedRoleId, resource, pending, error, storageError, recoveryAvailable, onClose, onSave }: CreationModalStatus & { roles: Role[]; selectedRoleId: string; resource?: RoleResource; onClose: () => void; onSave: (request: RoleResourceFormRequest) => void }) {
  const editing = Boolean(resource)
  const titleInputRef = useRef<HTMLInputElement>(null)
  const [roleId, setRoleId] = useState((resource?.roleId ?? selectedRoleId) || roles[0]?.id || '')
  const [title, setTitle] = useState(resource?.title ?? '')
  const [url, setUrl] = useState(resource?.url ?? '')
  const [description, setDescription] = useState(resource?.description ?? '')
  const [titleValidationMessage, setTitleValidationMessage] = useState('')
  const [urlValidationMessage, setUrlValidationMessage] = useState('')
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (pending || !roleId) return
    if (!title.trim()) {
      setTitleValidationMessage('자료 이름을 입력해 주세요.')
      titleInputRef.current?.focus()
      return
    }
    const normalizedUrl = url.trim()
    try {
      const parsed = new URL(normalizedUrl)
      if ((parsed.protocol !== 'http:' && parsed.protocol !== 'https:')
        || !parsed.hostname || parsed.username || parsed.password) throw new Error('invalid url')
    } catch {
      setUrlValidationMessage('사용자 정보 없이 http 또는 https로 시작하는 전체 링크를 입력해 주세요.')
      return
    }
    setTitleValidationMessage('')
    setUrlValidationMessage('')
    onSave({ roleId, title: title.trim(), url: normalizedUrl, description: description.trim() || null })
  }
  return (
    <ModalShell
      title={editing ? '참고 자료 수정' : '역할에 참고 자료 연결'}
      description="문서나 외부 링크를 역할에 연결해, 담당자가 바뀌어도 같은 자료를 바로 찾게 합니다."
      closeDisabled={pending}
      onClose={onClose}
    >
      <form className="modal-form" noValidate onSubmit={submit}>
        <label><span>역할</span><select required value={roleId} onChange={(event) => setRoleId(event.target.value)}>{roles.map((role) => <option key={role.id} value={role.id}>{role.name}</option>)}</select></label>
        <label><span>자료 이름</span><input ref={titleInputRef} autoFocus required maxLength={200} aria-invalid={Boolean(titleValidationMessage)} aria-describedby={titleValidationMessage ? 'role-resource-title-error' : undefined} value={title} onChange={(event) => { setTitle(event.target.value); setTitleValidationMessage('') }} placeholder="예: 질문 정리 가이드" /></label>
        {titleValidationMessage && <p id="role-resource-title-error" className="form-error" role="alert">{titleValidationMessage}</p>}
        <label><span>링크</span><input type="url" required maxLength={2048} autoCapitalize="none" spellCheck={false} aria-invalid={Boolean(urlValidationMessage)} aria-describedby={urlValidationMessage ? 'role-resource-url-error' : undefined} value={url} onChange={(event) => { setUrl(event.target.value); setUrlValidationMessage('') }} placeholder="https://docs.example.com/guide" /></label>
        <label><span>자료 설명</span><textarea maxLength={1000} value={description} onChange={(event) => setDescription(event.target.value)} placeholder="이 자료를 언제, 어떻게 사용하는지 적어주세요" rows={3} /></label>
        {urlValidationMessage && <p id="role-resource-url-error" className="form-error" role="alert">{urlValidationMessage}</p>}
        {editing ? <FormError error={error} /> : <CreationFormFeedback error={error} storageError={storageError} recoveryAvailable={recoveryAvailable} />}
        <FormActions pending={pending} submitLabel={editing ? '변경 저장' : '자료 연결하기'} pendingLabel={editing ? '자료 저장하는 중…' : '자료 연결하는 중…'} onClose={onClose} />
      </form>
    </ModalShell>
  )
}

function RoutineModal({ roles, selectedRoleId, routine, pending, error, storageError, recoveryAvailable, onClose, onSave }: CreationModalStatus & { roles: Role[]; selectedRoleId: string; routine?: Routine; onClose: () => void; onSave: (request: RoutineFormRequest) => void }) {
  const editing = Boolean(routine)
  const [title, setTitle] = useState(routine?.title ?? '')
  const [phase, setPhase] = useState<RoutinePhase>(routine?.phase ?? 'BEFORE')
  const [dueLabel, setDueLabel] = useState(routine?.dueLabel ?? '')
  const [ownerRoleId, setOwnerRoleId] = useState((routine?.ownerRoleId ?? selectedRoleId) || roles[0]?.id || '')
  const [detail, setDetail] = useState(routine?.detail ?? '')
  const submit = (event: FormEvent) => { event.preventDefault(); if (pending || !ownerRoleId) return; onSave({ title: title.trim(), phase, dueLabel: dueLabel.trim(), ownerRoleId, detail: detail.trim() }) }
  return (
    <ModalShell title={editing ? '루틴 수정' : '반복 루틴 만들기'} description={editing ? '운영 단계와 담당 역할, 기한 문구를 현재 반복 방식에 맞게 고쳐주세요.' : '모임 전·중·후에 누가 무엇을 넘길지 운영 리듬에 추가합니다.'} closeDisabled={editing && pending} onClose={onClose}>
      <form className="modal-form" onSubmit={submit}>
        <label><span>루틴 이름</span><input autoFocus required value={title} onChange={(event) => setTitle(event.target.value)} placeholder="예: 문제 5개 선정" /></label>
        <div className="form-grid"><label><span>운영 단계</span><select value={phase} onChange={(event) => setPhase(event.target.value as RoutinePhase)}>{(Object.keys(phaseCopy) as RoutinePhase[]).map((value) => <option key={value} value={value}>{phaseCopy[value]}</option>)}</select></label><label><span>담당 역할</span><select required value={ownerRoleId} onChange={(event) => setOwnerRoleId(event.target.value)}>{roles.map((role) => <option key={role.id} value={role.id}>{role.name}</option>)}</select></label></div>
        <label><span>언제까지</span><input required value={dueLabel} onChange={(event) => setDueLabel(event.target.value)} placeholder="예: 수요일 18:00" /></label>
        <label><span>세부 설명</span><textarea required value={detail} onChange={(event) => setDetail(event.target.value)} placeholder="완료 기준이나 다음 역할이 알아야 할 내용을 적어주세요" rows={3} /></label>
        {editing ? <FormError error={error} /> : <CreationFormFeedback error={error} storageError={storageError} recoveryAvailable={recoveryAvailable} />}
        <FormActions pending={pending} submitLabel={editing ? '변경 저장' : '루틴 만들기'} pendingLabel={editing ? '루틴 저장하는 중…' : '루틴 만드는 중…'} onClose={onClose} />
      </form>
    </ModalShell>
  )
}

function SeasonRoundModal({ season, roundCount, pending, error, storageError, recoveryAvailable, onClose, onSave }: CreationModalStatus & { season: Season; roundCount: number; onClose: () => void; onSave: (request: CreateSeasonRoundRequest) => void }) {
  const [name, setName] = useState(`${roundCount + 1}회차`)
  const [meetingDate, setMeetingDate] = useState(clampToSeason(localTodayValue(), season))
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (pending || !name.trim() || !meetingDate) return
    onSave({ name: name.trim(), meetingDate })
  }
  return (
    <ModalShell title="회차 만들기" description="현재 루틴을 이번 운영의 실행 목록으로 복사합니다. 이후 루틴을 바꿔도 이 회차의 기록은 그대로 남아요." onClose={onClose}>
      <form className="modal-form" onSubmit={submit}>
        <label><span>회차 이름</span><input autoFocus required maxLength={100} value={name} onChange={(event) => setName(event.target.value)} placeholder="예: 3회차" /></label>
        <label><span>모임 날짜</span><input type="date" required min={season.startDate} max={season.endDate} value={meetingDate} onChange={(event) => setMeetingDate(event.target.value)} /><small>{formatLocalDate(season.startDate)}부터 {formatLocalDate(season.endDate)} 사이에서 선택해 주세요.</small></label>
        <CreationFormFeedback error={error} storageError={storageError} recoveryAvailable={recoveryAvailable} />
        <FormActions pending={pending} submitLabel="회차 만들기" pendingLabel="회차 만드는 중…" onClose={onClose} />
      </form>
    </ModalShell>
  )
}

function HandoffItemModal({ roles, selectedRoleId, pending, error, storageError, recoveryAvailable, onClose, onSave }: CreationModalStatus & { roles: Role[]; selectedRoleId: string; onClose: () => void; onSave: (item: CreateHandoffItemRequest) => void }) {
  const [roleId, setRoleId] = useState(selectedRoleId || roles[0]?.id || '')
  const [label, setLabel] = useState('')
  const [category, setCategory] = useState<HandoffCategory>('RESPONSIBILITY')
  const submit = (event: FormEvent) => { event.preventDefault(); if (pending || !roleId) return; onSave({ roleId, label: label.trim(), category }) }
  return (
    <ModalShell title="바통북 항목 추가" description="다음 담당자가 바로 움직이려면 꼭 알아야 할 내용 하나를 남겨주세요." onClose={onClose}>
      <form className="modal-form" onSubmit={submit}>
        <label><span>역할</span><select required value={roleId} onChange={(event) => setRoleId(event.target.value)}>{roles.map((role) => <option key={role.id} value={role.id}>{role.name}</option>)}</select></label>
        <label><span>남길 내용</span><input autoFocus required value={label} onChange={(event) => setLabel(event.target.value)} placeholder="예: 문제 선정 기준 문서 링크" /></label>
        <label><span>항목 종류</span><select value={category} onChange={(event) => setCategory(event.target.value as HandoffCategory)}>{(Object.keys(categoryCopy) as HandoffCategory[]).map((value) => <option key={value} value={value}>{categoryCopy[value]}</option>)}</select></label>
        <CreationFormFeedback error={error} storageError={storageError} recoveryAvailable={recoveryAvailable} /><FormActions pending={pending} submitLabel="항목 추가하기" pendingLabel="항목 추가하는 중…" onClose={onClose} />
      </form>
    </ModalShell>
  )
}

function FormActions({ pending, submitLabel, pendingLabel, onClose }: { pending: boolean; submitLabel: string; pendingLabel: string; onClose: () => void }) {
  return <div className="form-actions"><button type="button" className="secondary-button" onClick={onClose} disabled={pending}>취소</button><button type="submit" className="primary-button" disabled={pending}>{pending ? pendingLabel : submitLabel}</button></div>
}

function splitList(value: string) {
  return [...new Set(value.split(/[\n,]/).map((item) => item.trim()).filter(Boolean))]
}

function HandoffPreview({ role, members, routines, decisions, resources, items, progress, onClose }: { role: Role; members: Member[]; routines: Routine[]; decisions: Decision[]; resources: RoleResource[]; items: HandoffItem[]; progress: number; onClose: () => void }) {
  const owner = getMember(members, role.currentMemberId)
  const next = getMember(members, role.nextMemberId)
  const relatedDecisions = decisions.filter((decision) => decision.roleIds.includes(role.id))
  const remainingItems = items.filter((item) => !item.completed)
  return (
    <ModalShell title={`${role.name} 바통북`} description={`${owner?.name ?? '이전 담당자'}에서 ${next?.name ?? '다음 담당자'}에게 이어질 역할 기록입니다.`} onClose={onClose}>
      <div className="book-preview">
        <div className="book-progress"><span>준비도</span><strong>{progress}%</strong></div>
        <section><span>01 · 역할의 목적</span><p>{role.purpose}</p></section>
        <section><span>02 · 반복하는 일</span>{routines.length ? <ul>{routines.map((routine) => <li key={routine.id}>{routine.title} · {routine.dueLabel}</li>)}</ul> : <p>연결된 반복 루틴이 아직 없습니다.</p>}</section>
        <section><span>03 · 중요한 결정</span>{relatedDecisions.length ? relatedDecisions.map((decision) => <blockquote key={decision.id}>“{decision.title}”<small>{decision.reason}</small></blockquote>) : <p>연결된 결정이 아직 없습니다.</p>}</section>
        <section><span>04 · 참고 자료</span>{resources.length ? <ul className="book-resource-links">{resources.map((resource) => <li key={resource.id}><a href={resource.url} target="_blank" rel="noopener noreferrer" aria-label={`${resource.title} 새 창에서 열기`}>{resource.title}</a>{resource.description && <small>{resource.description}</small>}</li>)}</ul> : <p>연결된 참고 자료가 아직 없습니다.</p>}</section>
        <section><span>05 · 남은 정리</span>{remainingItems.length ? <ul>{remainingItems.map((item) => <li key={item.id}>{item.label}</li>)}</ul> : <p>남은 정리가 없습니다.</p>}</section>
        <button type="button" className="primary-button full-button" onClick={onClose}>미리보기 닫기</button>
      </div>
    </ModalShell>
  )
}
