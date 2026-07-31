import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { ApiError } from '@/shared/api/ApiError'
import { Icon } from '@/shared/ui/Icon'
import {
  initialRecordSearchFilters,
  RecordSearchView,
} from '@/features/records/RecordSearchView'
import type {
  RecordSearchFilters,
  RecordSearchResult,
} from '@/features/records/recordSearch'
import type { WorkspaceScope } from './api'
import {
  useAcceptRoleHandoffMutation,
  useCancelRoleHandoffMutation,
  useDecisionArchiveMutation,
  useHandoffCompletionMutation,
  useHandoffItemArchiveMutation,
  useRoutineExecutionCompletionMutation,
  useTransferRoleHandoffMutation,
  useUpdateSeasonEndingMutation,
  useUpdateRoundScheduleMutation,
  useUpdateSeasonMutation,
  useSeasonRoundArchiveMutation,
  useUpdateDecisionMutation,
  useUpdateHandoffItemMutation,
  useUpdateMemberDeactivationMutation,
  useUpdateMemberMutation,
  useUpdateRoleMutation,
  useUpdateRoleResourceMutation,
  useUpdateRoutineMutation,
  useUpdateSeasonRoundMutation,
  useWorkspaceQuery,
} from './queries'
import {
  NextSeasonModal,
  SeasonEditModal,
  SeasonEndedBanner,
  SeasonSwitcherModal,
} from './SeasonLifecycleModals'
import { useSeasonSuccessorCommand } from './useSeasonSuccessorCommand'
import {
  useCreateDecisionCommand,
  useCreateHandoffItemCommand,
  useCreateMemberCommand,
  useCreateRoleCommand,
  useCreateRoleResourceCommand,
  useCreateRoutineCommand,
  useCreateSeasonRoundCommand,
  usePrepareRoleHandoffCommand,
} from './useContentCreationCommand'
import {
  isWorkspaceAccessDenied,
  useWorkspaceAccessKeyFlow,
} from './useWorkspaceAccessKeyFlow'
import { useWorkspaceConflictRecovery } from './useWorkspaceConflictRecovery'
import {
  DecisionModal,
  HandoffItemModal,
  HandoffPreview,
  MemberModal,
  MemberManagementModal,
  RoleHandoffModal,
  RoleModal,
  RoleResourceModal,
  RoutineModal,
  RoundScheduleModal,
  type SeasonRoundFormRequest,
  SeasonRoundModal,
} from './WorkspaceModals'
import type {
  DecisionFormRequest,
  HandoffItemFormRequest,
  MemberFormRequest,
  RoleFormRequest,
  RoleResourceFormRequest,
  RoleHandoffModalMode,
  RoutineFormRequest,
  RoundScheduleFormRequest,
} from './WorkspaceModals'
import { AccessKeyModal, ShareLinkFallback } from './WorkspaceAccessModals'
import {
  hasWorkspaceAccessKeyRecovery,
  WorkspaceAccessKeyRecovery,
} from './WorkspaceAccessKeyRecovery'
import {
  HandoffView,
  MemoryView,
  MobileNav,
  MobileTopbar,
  RhythmView,
  RoleInspector,
  RolesView,
  Sidebar,
  TodayView,
  WorkspaceState,
  WorkspaceSyncStatus,
} from './WorkspaceViews'
import { formatPilotToday, pilotCalendarDate } from './seasonCalendar'
import {
  isActiveMember,
  isRoleHandoffLocked,
  latestRoleHandoff,
  mutationError,
} from './workspacePresentation'
import type {
  CancelRoleHandoffRequest,
  ContinuitySignal,
  CreateDecisionRequest,
  CreateHandoffItemRequest,
  CreateNextSeasonRequest,
  CreateSeasonRoundRequest,
  ConfirmRoleHandoffRequest,
  Decision,
  HandoffItem,
  Member,
  PrepareRoleHandoffRequest,
  Role,
  RoleHandoff,
  RoleResource,
  Routine,
  RoutineExecution,
  SeasonRound,
  TransferRoleHandoffRequest,
  UpdateSeasonRequest,
  ViewKey,
  WorkspaceProjection,
} from './types'

type ModalType = 'decision' | 'members' | 'member' | 'role' | 'roleResource' | 'routine' | 'round' | 'roundSchedule' | 'handoffItem' | 'roleHandoff' | 'handoffPreview' | 'shareLink' | 'accessKey' | 'seasonSwitcher' | 'seasonEdit' | 'seasonSuccessor' | null
type OpenModalType = Exclude<ModalType, null>
type Toast = { message: string; tone: 'success' | 'error' }
type RoundSelection = {
  roundId: string
  source: 'relevant-default' | 'user'
}

type RoleHandoffAction = {
  mode: RoleHandoffModalMode
  roleId: string
  handoffId?: string
}

type WorkspaceAppProps = WorkspaceScope & {
  accessDeniedAction?: ReactNode
  onWorkspaceLoaded?: (workspace: WorkspaceProjection) => void
  onSelectSeason: (seasonId: string, accessKey: string) => void
  onSeasonCreated: (seasonId: string, accessKey: string) => void
}

function useMediaQuery(query: string, onBeforeChange?: (matches: boolean) => void) {
  const onBeforeChangeRef = useRef(onBeforeChange)
  onBeforeChangeRef.current = onBeforeChange
  const [matches, setMatches] = useState(() =>
    typeof window !== 'undefined' && window.matchMedia(query).matches)

  useEffect(() => {
    const mediaQuery = window.matchMedia(query)
    const updateMatches = (event: MediaQueryListEvent) => {
      onBeforeChangeRef.current?.(event.matches)
      setMatches(event.matches)
    }
    setMatches(mediaQuery.matches)
    mediaQuery.addEventListener('change', updateMatches)
    return () => mediaQuery.removeEventListener('change', updateMatches)
  }, [query])

  return matches
}

function canReceiveFocus(element: HTMLElement | null) {
  return Boolean(element?.isConnected
    && !element.closest('[inert]')
    && !element.matches(':disabled')
    && element.getAttribute('aria-disabled') !== 'true'
    && element.getClientRects().length > 0)
}

function focusConnectedElement(preferred: HTMLElement | null) {
  const candidates = [
    preferred,
    ...document.querySelectorAll<HTMLElement>(
      '.inspector:not([inert]) .inspector-close, .main-surface',
    ),
  ]
  for (const candidate of candidates) {
    if (!canReceiveFocus(candidate)) continue
    candidate?.focus()
    if (document.activeElement === candidate) return
  }
}

function useModalSession() {
  const [modal, setModal] = useState<ModalType>(null)
  const modalRef = useRef<ModalType>(null)
  const openerRef = useRef<HTMLElement | null>(null)
  const generationRef = useRef(0)

  const openModal = (nextModal: OpenModalType) => {
    if (modalRef.current === null) {
      generationRef.current += 1
      openerRef.current = document.activeElement instanceof HTMLElement
        ? document.activeElement
        : null
    }
    modalRef.current = nextModal
    setModal(nextModal)
  }

  const closeModal = () => {
    if (modalRef.current === null) return

    const generation = generationRef.current
    const opener = openerRef.current
    modalRef.current = null
    setModal(null)
    window.requestAnimationFrame(() => {
      if (modalRef.current !== null || generationRef.current !== generation) return
      focusConnectedElement(opener)
      openerRef.current = null
    })
  }

  return { modal, openModal, closeModal }
}

function compareSeasonRounds(left: SeasonRound, right: SeasonRound) {
  const dateOrder = (left.meetingDate ?? '').localeCompare(right.meetingDate ?? '')
  if (dateOrder !== 0) return dateOrder
  const nameOrder = left.name.localeCompare(right.name, 'ko')
  return nameOrder !== 0 ? nameOrder : left.id.localeCompare(right.id)
}

function sortedSeasonRounds(rounds: SeasonRound[]) {
  return [...rounds].sort(compareSeasonRounds)
}

function sortedArchivedSeasonRounds(rounds: SeasonRound[]) {
  return [...rounds].sort((left, right) => {
    const archiveOrder = (right.archivedAt ?? '').localeCompare(left.archivedAt ?? '')
    return archiveOrder !== 0 ? archiveOrder : compareSeasonRounds(left, right)
  })
}

function relevantSeasonRound(rounds: SeasonRound[]) {
  const priority = {
    OVERDUE: 0,
    IN_PROGRESS: 1,
    PLANNED: 2,
    COMPLETED: 3,
  } as const
  return [...rounds].sort((left, right) => {
    const statusOrder = priority[left.timingStatus] - priority[right.timingStatus]
    if (statusOrder !== 0) return statusOrder
    if (left.timingStatus === 'COMPLETED') return compareSeasonRounds(right, left)
    return compareSeasonRounds(left, right)
  })[0]
}

function isWorkspaceContentConflict(error: unknown) {
  return error instanceof ApiError && error.code === 'WORKSPACE_CONTENT_CONFLICT'
}

function useRecordBusyIds() {
  const busyIdsRef = useRef<ReadonlySet<string>>(new Set())
  const [busyIds, setBusyIds] = useState<ReadonlySet<string>>(busyIdsRef.current)

  const begin = (id: string) => {
    if (busyIdsRef.current.has(id)) return false
    const next = new Set(busyIdsRef.current)
    next.add(id)
    busyIdsRef.current = next
    setBusyIds(next)
    return true
  }

  const end = (id: string) => {
    if (!busyIdsRef.current.has(id)) return
    const next = new Set(busyIdsRef.current)
    next.delete(id)
    busyIdsRef.current = next
    setBusyIds(next)
  }

  return { busyIds, begin, end }
}

function useToast() {
  const [toast, setToast] = useState<Toast | null>(null)
  const timeoutIdRef = useRef<number | null>(null)

  useEffect(() => () => {
    if (timeoutIdRef.current !== null) window.clearTimeout(timeoutIdRef.current)
  }, [])

  const showToast = (message: string, tone: Toast['tone'] = 'success') => {
    if (timeoutIdRef.current !== null) window.clearTimeout(timeoutIdRef.current)
    setToast({ message, tone })
    timeoutIdRef.current = window.setTimeout(() => {
      setToast(null)
      timeoutIdRef.current = null
    }, 2800)
  }

  return { toast, showToast }
}

export default function WorkspaceApp({ teamId, seasonId, accessKey, accessDeniedAction, onWorkspaceLoaded, onSelectSeason, onSeasonCreated }: WorkspaceAppProps) {
  const queryClient = useQueryClient()
  const [currentAccessKey, setCurrentAccessKey] = useState(accessKey)
  const scope = { teamId, seasonId, accessKey: currentAccessKey }
  const workspaceQuery = useWorkspaceQuery(scope)
  const memberCreationCommand = useCreateMemberCommand(scope)
  const updateMemberMutation = useUpdateMemberMutation(scope)
  const updateMemberDeactivationMutation = useUpdateMemberDeactivationMutation(scope)
  const roleCreationCommand = useCreateRoleCommand(scope)
  const updateRoleMutation = useUpdateRoleMutation(scope)
  const prepareRoleHandoffCommand = usePrepareRoleHandoffCommand(scope)
  const transferRoleHandoffMutation = useTransferRoleHandoffMutation(scope)
  const acceptRoleHandoffMutation = useAcceptRoleHandoffMutation(scope)
  const cancelRoleHandoffMutation = useCancelRoleHandoffMutation(scope)
  const roleResourceCreationCommand = useCreateRoleResourceCommand(scope)
  const updateRoleResourceMutation = useUpdateRoleResourceMutation(scope)
  const routineCreationCommand = useCreateRoutineCommand(scope)
  const updateRoutineMutation = useUpdateRoutineMutation(scope)
  const roundCreationCommand = useCreateSeasonRoundCommand(scope)
  const updateSeasonRoundMutation = useUpdateSeasonRoundMutation(scope)
  const seasonRoundArchiveMutation = useSeasonRoundArchiveMutation(scope)
  const routineExecutionCompletionMutation = useRoutineExecutionCompletionMutation(scope)
  const decisionCreationCommand = useCreateDecisionCommand(scope)
  const updateDecisionMutation = useUpdateDecisionMutation(scope)
  const decisionArchiveMutation = useDecisionArchiveMutation(scope)
  const handoffItemCreationCommand = useCreateHandoffItemCommand(scope)
  const updateHandoffItemMutation = useUpdateHandoffItemMutation(scope)
  const handoffCompletionMutation = useHandoffCompletionMutation(scope)
  const handoffItemArchiveMutation = useHandoffItemArchiveMutation(scope)
  const updateSeasonMutation = useUpdateSeasonMutation(scope)
  const updateRoundScheduleMutation = useUpdateRoundScheduleMutation(scope)
  const updateSeasonEndingMutation = useUpdateSeasonEndingMutation(scope)
  const seasonSuccessorCommand = useSeasonSuccessorCommand(scope)

  const [view, setView] = useState<ViewKey>('today')
  const [recordSearchFilters, setRecordSearchFilters] = useState<RecordSearchFilters>(
    initialRecordSearchFilters,
  )
  const [selectedRoleId, setSelectedRoleId] = useState('')
  const [roundSelection, setRoundSelection] = useState<RoundSelection>({
    roundId: '',
    source: 'relevant-default',
  })
  const selectedRoundId = roundSelection.roundId

  useEffect(() => {
    setRecordSearchFilters(initialRecordSearchFilters)
  }, [seasonId])

  const selectRound = (roundId: string) => {
    setRoundSelection({ roundId, source: 'user' })
  }
  const { modal, openModal, closeModal } = useModalSession()
  const [editingMember, setEditingMember] = useState<Member | null>(null)
  const [editingRole, setEditingRole] = useState<Role | null>(null)
  const [editingRoleResource, setEditingRoleResource] = useState<RoleResource | null>(null)
  const [editingRoutine, setEditingRoutine] = useState<Routine | null>(null)
  const [editingRound, setEditingRound] = useState<SeasonRound | null>(null)
  const [editingDecision, setEditingDecision] = useState<Decision | null>(null)
  const [editingHandoffItem, setEditingHandoffItem] = useState<HandoffItem | null>(null)
  const [roleHandoffAction, setRoleHandoffAction] = useState<RoleHandoffAction | null>(null)
  const [inspectorOpen, setInspectorOpen] = useState(false)
  const inspectorOpenRef = useRef(false)
  const inspectorOpenerRef = useRef<HTMLElement | null>(null)
  const inspectorFocusGenerationRef = useRef(0)
  const inspectorModeFocusRef = useRef(false)
  const inspectorOverlay = useMediaQuery('(max-width: 1240px)', () => {
    const activeElement = document.activeElement
    inspectorModeFocusRef.current = activeElement instanceof HTMLElement
      && Boolean(activeElement.closest('.inspector'))
  })
  const { toast, showToast } = useToast()
  const {
    copyShareLink,
    pendingRotationIdempotencyKey,
    recoverPendingAccessKeyRotation,
    retryRotationJournalCleanup,
    rotateWorkspaceAccessKey,
    rotationCleanupRetryAvailable,
    rotationError,
    rotationPending,
    rotationStorageError,
    shareUrl,
  } = useWorkspaceAccessKeyFlow({
    scope,
    currentAccessKey,
    onAccessKeyChange: setCurrentAccessKey,
    onCloseModal: closeModal,
    onOpenShareLink: () => openModal('shareLink'),
    notify: showToast,
  })
  const {
    busyIds: busyRoundIds,
    begin: beginRoundOperation,
    end: endRoundOperation,
  } = useRecordBusyIds()
  const {
    busyIds: busyHandoffItemIds,
    begin: beginHandoffItemOperation,
    end: endHandoffItemOperation,
  } = useRecordBusyIds()
  const {
    recoveryStatus: conflictRecoveryStatus,
    beginRecovery: beginContentConflictRecovery,
    retryRecovery: retryContentConflictRecovery,
    ensureFreshWorkspace,
  } = useWorkspaceConflictRecovery({
    scopeKey: JSON.stringify([teamId, seasonId, currentAccessKey]),
    refetchWorkspace: () => workspaceQuery.refetch({ throwOnError: true }),
    discardEditors: () => {
      setEditingMember(null)
      setEditingRole(null)
      setEditingRoleResource(null)
      setEditingRoutine(null)
      setEditingRound(null)
      setEditingDecision(null)
      setEditingHandoffItem(null)
      setRoleHandoffAction(null)
      closeModal()
    },
    notify: showToast,
  })
  const handledSeasonEndedErrorRef = useRef<unknown>(null)
  const handledRoleHandoffConflictRef = useRef<unknown>(null)

  useEffect(() => {
    if (workspaceQuery.data) onWorkspaceLoaded?.(workspaceQuery.data)
  }, [onWorkspaceLoaded, workspaceQuery.data])

  useEffect(() => queryClient.getMutationCache().subscribe((event) => {
    const error = event.mutation?.state.error
    if (!(error instanceof ApiError)) return
    if (error.code === 'ROLE_HANDOFF_STATE_CONFLICT') {
      if (handledRoleHandoffConflictRef.current === error) return
      handledRoleHandoffConflictRef.current = error
      beginContentConflictRecovery(
        '다른 구성원이 먼저 바꾼 최신 역할 바통 상태를 불러왔어요.',
      )
      return
    }
    if (error.code !== 'SEASON_ENDED'
      || handledSeasonEndedErrorRef.current === error) return

    handledSeasonEndedErrorRef.current = error
    setEditingMember(null)
    setEditingRole(null)
    setEditingRoleResource(null)
    setEditingRoutine(null)
    setEditingRound(null)
    setEditingDecision(null)
    setEditingHandoffItem(null)
    setRoleHandoffAction(null)
    closeModal()
    showToast('다른 구성원이 시즌을 종료했어요. 최신 기록을 읽기 전용으로 다시 불러옵니다.', 'error')
    void workspaceQuery.refetch()
  }), [beginContentConflictRecovery, queryClient, workspaceQuery.refetch])

  useLayoutEffect(() => {
    if (!inspectorModeFocusRef.current) return

    inspectorModeFocusRef.current = false
    const target = inspectorOverlay && inspectorOpenRef.current
      ? document.querySelector<HTMLElement>('.inspector:not([inert]) .inspector-close')
      : document.querySelector<HTMLElement>('.main-surface')
    focusConnectedElement(target)
  }, [inspectorOverlay])

  useLayoutEffect(() => {
    if (!conflictRecoveryStatus) return

    const activeElement = document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null
    if (activeElement !== document.body && canReceiveFocus(activeElement)) return
    focusConnectedElement(null)
  }, [conflictRecoveryStatus])

  useEffect(() => {
    const rounds = sortedSeasonRounds(
      (workspaceQuery.data?.rounds ?? []).filter((round) => !round.archivedAt),
    )
    setRoundSelection((current) => {
      if (current.source === 'user'
        && rounds.some((round) => round.id === current.roundId)) return current

      const relevantRoundId = relevantSeasonRound(rounds)?.id ?? ''
      if (current.source === 'relevant-default'
        && current.roundId === relevantRoundId) return current
      return { roundId: relevantRoundId, source: 'relevant-default' }
    })
  }, [workspaceQuery.data?.rounds])

  const workspaceAccessDenied = isWorkspaceAccessDenied(workspaceQuery.error)

  if (workspaceQuery.isPending) {
    return <WorkspaceState title="작업 공간을 불러오는 중이에요" description="팀의 바통과 이번 시즌 기록을 모으고 있습니다." busy />
  }

  if (!workspaceQuery.data || workspaceAccessDenied) {
    const isAccessDenied = workspaceAccessDenied
    const accessKeyRecovery = isAccessDenied && hasWorkspaceAccessKeyRecovery({
      pendingIdempotencyKey: pendingRotationIdempotencyKey,
      rotationError,
    })
      ? (
          <WorkspaceAccessKeyRecovery
            pendingIdempotencyKey={pendingRotationIdempotencyKey}
            rotationError={rotationError}
            storageError={rotationStorageError}
            pending={rotationPending}
            cleanupRetryAvailable={rotationCleanupRetryAvailable}
            onRecover={recoverPendingAccessKeyRotation}
            onRetryCleanup={retryRotationJournalCleanup}
          />
        )
      : undefined
    return (
      <WorkspaceState
        title="작업 공간을 불러오지 못했어요"
        description={mutationError(workspaceQuery.error)}
        action={accessKeyRecovery ?? (isAccessDenied && accessDeniedAction
          ? accessDeniedAction
          : <button type="button" className="primary-button" onClick={() => workspaceQuery.refetch()}>다시 시도하기</button>)}
      />
    )
  }

  const workspace = workspaceQuery.data
  const calendarNow = new Date()
  const calendarDate = pilotCalendarDate(calendarNow, workspace.season.timeZone)
  const calendarLabel = formatPilotToday(calendarNow, workspace.season.timeZone)
  const {
    roles,
    resources,
    routines,
    rounds,
    decisions,
    handoffItems,
    roleHandoffs = [],
    members,
  } = workspace
  const seasons = workspace.seasons?.length ? workspace.seasons : [workspace.season]
  const activeMembers = members.filter(isActiveMember)
  const seasonEnded = Boolean(workspace.season.endedAt)
  const contentChangesDisabled = seasonEnded || Boolean(conflictRecoveryStatus)
  const activeRounds = rounds.filter((round) => !round.archivedAt)
  const archivedRounds = rounds.filter((round) => round.archivedAt)
  const activeDecisions = decisions.filter((decision) => !decision.archivedAt)
  const archivedDecisions = decisions.filter((decision) => decision.archivedAt)
  const activeHandoffItems = handoffItems.filter((item) => !item.archivedAt)
  const archivedHandoffItems = handoffItems.filter((item) => item.archivedAt)
  const activeWorkspace = {
    ...workspace,
    rounds: activeRounds,
    decisions: activeDecisions,
    handoffItems: activeHandoffItems,
  }
  const orderedActiveRounds = sortedSeasonRounds(activeRounds)
  const orderedArchivedRounds = sortedArchivedSeasonRounds(archivedRounds)
  const selectedRound = orderedActiveRounds.find((round) => round.id === selectedRoundId)
    ?? relevantSeasonRound(orderedActiveRounds)
  const selectedRole = roles.find((role) => role.id === selectedRoleId) ?? roles[0]
  const currentEditingRole = editingRole
    ? roles.find((role) => role.id === editingRole.id) ?? editingRole
    : undefined
  const editingRoleAssignmentLocked = currentEditingRole
    ? latestRoleHandoff(roleHandoffs, currentEditingRole.id)?.status === 'PREPARING'
    : false
  const effectiveSelectedRoleId = selectedRole?.id ?? ''
  const selectedRoleHandoff = selectedRole
    ? latestRoleHandoff(roleHandoffs, selectedRole.id)
    : undefined
  const selectedRoleLocked = selectedRole
    ? isRoleHandoffLocked(roleHandoffs, selectedRole.id)
    : false
  const lockedRoleIds = new Set(
    roleHandoffs
      .filter((handoff) => handoff.status === 'TRANSFERRED')
      .map((handoff) => handoff.roleId),
  )
  const roleHandoffActionRole = roleHandoffAction
    ? roles.find((role) => role.id === roleHandoffAction.roleId)
    : undefined
  const roleHandoffActionTarget = roleHandoffAction?.handoffId
    ? roleHandoffs.find((handoff) => handoff.id === roleHandoffAction.handoffId)
    : undefined
  const pendingCount = selectedRound?.routineExecutions.filter((execution) => execution.status !== 'DONE').length ?? 0
  const completedCount = selectedRound?.routineExecutions.filter((execution) => execution.status === 'DONE').length ?? 0
  const hasPendingRoleCreation = modal === 'role'
    && !editingRole
    && roleCreationCommand.hasPending()
  const hasPendingMemberCreation = modal === 'member'
    && !editingMember
    && memberCreationCommand.hasPending()
  const pendingMemberDeactivationId = updateMemberDeactivationMutation.isPending
    ? updateMemberDeactivationMutation.variables?.id ?? null
    : null
  const hasPendingRoutineCreation = modal === 'routine'
    && !editingRoutine
    && routineCreationCommand.hasPending()
  const hasPendingRoundCreation = modal === 'round'
    && !editingRound
    && roundCreationCommand.hasPending()
  const hasPendingDecisionCreation = modal === 'decision'
    && !editingDecision
    && decisionCreationCommand.hasPending()
  const hasPendingHandoffCreation = modal === 'handoffItem'
    && !editingHandoffItem
    && handoffItemCreationCommand.hasPending()
  const hasPendingRoleHandoffPreparation = modal === 'roleHandoff'
    && roleHandoffAction?.mode === 'prepare'
    && prepareRoleHandoffCommand.hasPending()
  const hasPendingRoleResourceCreation = modal === 'roleResource'
    && !editingRoleResource
    && roleResourceCreationCommand.hasPending()
  const hasSuccessor = seasons.some((candidate) =>
    candidate.previousSeasonId === workspace.season.id)
  const activeRoleHandoffMutation = roleHandoffAction?.mode === 'transfer'
    ? transferRoleHandoffMutation
    : roleHandoffAction?.mode === 'accept'
      ? acceptRoleHandoffMutation
      : cancelRoleHandoffMutation
  const roleHandoffModalPending = roleHandoffAction?.mode === 'prepare'
    ? prepareRoleHandoffCommand.isPending
    : activeRoleHandoffMutation.isPending
  const roleHandoffModalError = roleHandoffAction?.mode === 'prepare'
    ? prepareRoleHandoffCommand.error
    : activeRoleHandoffMutation.error

  const dismissInspector = (restoreFocus: boolean) => {
    if (!inspectorOpenRef.current) return

    const generation = inspectorFocusGenerationRef.current
    const opener = inspectorOpenerRef.current
    inspectorOpenRef.current = false
    setInspectorOpen(false)

    if (!restoreFocus) {
      inspectorFocusGenerationRef.current += 1
      inspectorOpenerRef.current = null
      return
    }

    window.requestAnimationFrame(() => {
      if (inspectorOpenRef.current
        || inspectorFocusGenerationRef.current !== generation) return
      focusConnectedElement(opener)
      inspectorOpenerRef.current = null
    })
  }

  const selectRole = (roleId: string, openInspector = true) => {
    setSelectedRoleId(roleId)
    if (!openInspector) {
      dismissInspector(false)
      return
    }

    if (inspectorOverlay && !inspectorOpenRef.current) {
      inspectorFocusGenerationRef.current += 1
      inspectorOpenerRef.current = document.activeElement instanceof HTMLElement
        ? document.activeElement
        : null
    }
    inspectorOpenRef.current = true
    setInspectorOpen(true)
  }

  const openView = (key: ViewKey) => {
    setView(key)
    dismissInspector(false)
  }

  const openRecordSearchResult = (result: RecordSearchResult) => {
    if (result.kind === 'decision') {
      openView('memory')
      window.requestAnimationFrame(() => {
        const target = document.querySelector<HTMLElement>(
          `[data-decision-id="${result.id}"]`,
        )
        target?.scrollIntoView({ block: 'center' })
        focusConnectedElement(target)
      })
      return
    }
    if (result.kind === 'handoff') {
      setSelectedRoleId(result.roleId)
      openView('handoff')
      window.requestAnimationFrame(() => {
        const target = document.querySelector<HTMLElement>(
          `[data-handoff-item-id="${result.id}"]`,
        )
        target?.scrollIntoView({ block: 'center' })
        focusConnectedElement(target)
      })
      return
    }

    setView('roles')
    selectRole(result.roleId)
  }

  const openContinuitySignal = (signal: ContinuitySignal) => {
    if (signal.type === 'ROUTINE_REPEATEDLY_OVERDUE') {
      setSelectedRoleId(signal.roleId)
      openView('rhythm')
    } else if (signal.type === 'HANDOFF_INCOMPLETE') {
      setSelectedRoleId(signal.roleId)
      openView('handoff')
    } else {
      setView('roles')
      selectRole(signal.roleId)
    }

    window.requestAnimationFrame(() => {
      let target: HTMLElement | null = null
      if (signal.type === 'ROUTINE_REPEATEDLY_OVERDUE' && signal.routineId) {
        const routineRow = [...document.querySelectorAll<HTMLElement>('.routine-row')]
          .find((row) => row.dataset.routineId === signal.routineId)
        target = routineRow?.querySelector<HTMLElement>('.routine-copy') ?? null
      } else if (signal.type === 'HANDOFF_INCOMPLETE') {
        target = document.querySelector<HTMLElement>(
          '.handoff-role-tabs [role="tab"][aria-selected="true"]',
        )
      } else {
        target = document.querySelector<HTMLElement>('.role-row.selected .role-row-open')
      }
      focusConnectedElement(target)
    })
  }

  const handoffProgress = (roleId: string) => {
    const items = activeHandoffItems.filter((item) => item.roleId === roleId)
    if (!items.length) return 0
    return Math.round((items.filter((item) => item.completed).length / items.length) * 100)
  }

  const openSeasonSwitcher = () => {
    updateSeasonEndingMutation.reset()
    openModal('seasonSwitcher')
  }

  const openSeasonEdit = () => {
    if (workspace.season.endedAt) return
    updateSeasonMutation.reset()
    openModal('seasonEdit')
  }

  const openRoundSchedule = () => {
    if (workspace.season.endedAt) return
    updateRoundScheduleMutation.reset()
    openModal('roundSchedule')
  }

  const openSeasonSuccessor = () => {
    if (hasSuccessor) {
      openSeasonSwitcher()
      showToast('이미 이어진 다음 시즌을 목록에서 열어 주세요.')
      return
    }
    seasonSuccessorCommand.reset()
    openModal('seasonSuccessor')
  }

  const selectSeason = (nextSeasonId: string) => {
    if (nextSeasonId === workspace.season.id) return
    closeModal()
    onSelectSeason(nextSeasonId, currentAccessKey)
  }

  const saveSeason = (request: UpdateSeasonRequest) => {
    updateSeasonMutation.mutate(request, {
      onSuccess: () => {
        closeModal()
        showToast('시즌 이름과 기간을 수정했어요.')
      },
    })
  }

  const saveRoundSchedule = (request: RoundScheduleFormRequest) => {
    updateRoundScheduleMutation.mutate(request, {
      onSuccess: () => {
        closeModal()
        showToast(request.enabled
          ? '자동 회차 일정을 저장했어요.'
          : '자동 회차 생성을 일시중지했어요. 기존 회차는 그대로 남습니다.')
      },
    })
    return true
  }

  const toggleSeasonEnding = () => {
    if (updateSeasonEndingMutation.isPending) return
    const ending = !workspace.season.endedAt
    const confirmed = window.confirm(ending
      ? '시즌을 종료하면 역할, 운영, 기록과 바통을 더 이상 바꿀 수 없습니다. 종료할까요?'
      : '이 시즌을 다시 열면 기록을 다시 수정할 수 있습니다. 다시 열까요?')
    if (!confirmed) return

    updateSeasonEndingMutation.mutate({ ended: ending }, {
      onSuccess: () => showToast(ending
        ? '시즌을 종료하고 기록을 읽기 전용으로 보존했어요.'
        : '시즌을 다시 열었어요.'),
    })
  }

  const createSuccessor = (request: CreateNextSeasonRequest) => {
    seasonSuccessorCommand.submit(request, (result) => {
      showToast('다음 시즌을 만들었어요.')
      onSeasonCreated(result.season.id, currentAccessKey)
    })
  }

  const openRoleModal = () => {
    setEditingRole(null)
    roleCreationCommand.reset()
    openModal('role')
  }

  const openMemberManagementModal = () => {
    setEditingMember(null)
    updateMemberMutation.reset()
    updateMemberDeactivationMutation.reset()
    openModal('members')
  }

  const openMemberModal = () => {
    setEditingMember(null)
    memberCreationCommand.reset()
    openModal('member')
  }

  const openMemberEditModal = (member: Member) => {
    if (!ensureFreshWorkspace()) return
    updateMemberMutation.reset()
    setEditingMember(member)
    openModal('member')
  }

  const returnToMemberManagement = () => {
    setEditingMember(null)
    memberCreationCommand.reset()
    updateMemberMutation.reset()
    openModal('members')
  }

  const openRoutineModal = () => {
    if (!roles.length) {
      setView('roles')
      showToast('루틴을 연결할 역할부터 만들어 주세요.', 'error')
      return
    }
    setEditingRoutine(null)
    routineCreationCommand.reset()
    openModal('routine')
  }

  const openRoundModal = () => {
    if (!routines.length) {
      showToast('회차를 만들기 전에 반복 루틴을 하나 이상 준비해 주세요.', 'error')
      return
    }
    setEditingRound(null)
    roundCreationCommand.reset()
    openModal('round')
  }

  const openRoleEditModal = (role: Role) => {
    if (!ensureFreshWorkspace()) return
    if (isRoleHandoffLocked(roleHandoffs, role.id)) {
      setSelectedRoleId(role.id)
      setView('handoff')
      showToast('전달한 역할은 수락하거나 취소한 뒤 수정할 수 있어요.', 'error')
      return
    }
    updateRoleMutation.reset()
    setEditingRole(role)
    openModal('role')
  }

  const openRoleResourceModal = () => {
    if (!roles.length) {
      setView('roles')
      showToast('자료를 연결할 역할부터 만들어 주세요.', 'error')
      return
    }
    if (selectedRole && isRoleHandoffLocked(roleHandoffs, selectedRole.id)) {
      setView('handoff')
      showToast('전달한 바통은 수락하거나 취소한 뒤 자료를 추가할 수 있어요.', 'error')
      return
    }
    setEditingRoleResource(null)
    roleResourceCreationCommand.reset()
    openModal('roleResource')
  }

  const openRoleResourceEditModal = (resource: RoleResource) => {
    if (!ensureFreshWorkspace()) return
    if (isRoleHandoffLocked(roleHandoffs, resource.roleId)) {
      setSelectedRoleId(resource.roleId)
      setView('handoff')
      showToast('전달한 바통은 수락하거나 취소한 뒤 자료를 수정할 수 있어요.', 'error')
      return
    }
    updateRoleResourceMutation.reset()
    setEditingRoleResource(resource)
    openModal('roleResource')
  }

  const openRoutineEditModal = (routine: Routine) => {
    if (!ensureFreshWorkspace()) return
    updateRoutineMutation.reset()
    setEditingRoutine(routine)
    openModal('routine')
  }

  const openRoundEditModal = (round: SeasonRound) => {
    if (!ensureFreshWorkspace()) return
    if (round.archivedAt) {
      showToast('보관한 회차는 복원한 뒤 수정해 주세요.', 'error')
      return
    }
    if (busyRoundIds.has(round.id)) return
    updateSeasonRoundMutation.reset()
    setEditingRound(round)
    openModal('round')
  }

  const openDecisionModal = () => {
    if (!roles.length || !activeMembers.length) {
      showToast('결정에 연결할 역할과 활동 중인 작성자부터 준비해 주세요.', 'error')
      return
    }
    setEditingDecision(null)
    decisionCreationCommand.reset()
    openModal('decision')
  }

  const openDecisionEditModal = (decision: Decision) => {
    if (!ensureFreshWorkspace()) return
    updateDecisionMutation.reset()
    setEditingDecision(decision)
    openModal('decision')
  }

  const openHandoffItemModal = () => {
    if (!roles.length) {
      setView('roles')
      showToast('바통을 남길 역할부터 만들어 주세요.', 'error')
      return
    }
    if (selectedRole && isRoleHandoffLocked(roleHandoffs, selectedRole.id)) {
      showToast('전달한 바통은 수락하거나 취소한 뒤 항목을 추가할 수 있어요.', 'error')
      return
    }
    setEditingHandoffItem(null)
    handoffItemCreationCommand.reset()
    openModal('handoffItem')
  }

  const openHandoffItemEditModal = (item: HandoffItem) => {
    if (!ensureFreshWorkspace()) return
    if (isRoleHandoffLocked(roleHandoffs, item.roleId)) {
      showToast('전달한 바통은 수락하거나 취소한 뒤 항목을 수정할 수 있어요.', 'error')
      return
    }
    if (busyHandoffItemIds.has(item.id)) return
    updateHandoffItemMutation.reset()
    setEditingHandoffItem(item)
    openModal('handoffItem')
  }

  const openRoleHandoffModal = (
    mode: RoleHandoffModalMode,
    role: Role,
    handoff?: RoleHandoff,
  ) => {
    if (!ensureFreshWorkspace() || contentChangesDisabled) return
    prepareRoleHandoffCommand.reset()
    transferRoleHandoffMutation.reset()
    acceptRoleHandoffMutation.reset()
    cancelRoleHandoffMutation.reset()
    setRoleHandoffAction({
      mode,
      roleId: role.id,
      handoffId: handoff?.id,
    })
    openModal('roleHandoff')
  }

  const addRole = (request: RoleFormRequest) => {
    return roleCreationCommand.submit(request, () => {
      closeModal()
      setView('roles')
      showToast('새 역할을 팀의 책임 지도에 추가했어요.')
    })
  }

  const addMember = (request: MemberFormRequest) => {
    return memberCreationCommand.submit(request, (createdMember) => {
      setEditingMember(null)
      closeModal()
      setView('roles')
      showToast(`${createdMember.name}님을 팀 구성원으로 추가했어요.`)
    })
  }

  const updateExistingMember = (request: MemberFormRequest) => {
    if (!ensureFreshWorkspace()) return false
    if (!editingMember) return false
    updateMemberMutation.mutate({ id: editingMember.id, request }, {
      onSuccess: (updatedMember) => {
        setEditingMember(null)
        openModal('members')
        showToast(`${updatedMember.name}님의 표시 이름을 수정했어요.`)
      },
      onError: (error) => {
        if (!isWorkspaceContentConflict(error)) return
        beginContentConflictRecovery(
          '다른 구성원이 먼저 바꾼 최신 구성원 정보를 불러왔어요.',
        )
      },
    })
    return true
  }

  const toggleMemberDeactivation = (member: Member) => {
    if (!ensureFreshWorkspace() || updateMemberDeactivationMutation.isPending) return
    const deactivated = isActiveMember(member)
    updateMemberDeactivationMutation.reset()
    updateMemberDeactivationMutation.mutate({
      id: member.id,
      request: { deactivated },
    }, {
      onSuccess: (updatedMember) => {
        showToast(deactivated
          ? `${updatedMember.name}님의 활동을 종료했어요. 기존 기록의 이름은 유지됩니다.`
          : `${updatedMember.name}님을 다시 활성화했어요.`)
      },
      onError: (error) => {
        if (isWorkspaceContentConflict(error)) {
          beginContentConflictRecovery(
            '다른 구성원이 먼저 바꾼 최신 구성원 정보를 불러왔어요.',
          )
          return
        }
        showToast(
          `구성원 활동 상태를 바꾸지 못했어요. ${mutationError(error)}`,
          'error',
        )
      },
    })
  }

  const updateExistingRole = (request: RoleFormRequest) => {
    if (!ensureFreshWorkspace()) return false
    if (!editingRole) return false
    if (isRoleHandoffLocked(roleHandoffs, editingRole.id)) {
      showToast('전달한 역할은 수락하거나 취소한 뒤 수정할 수 있어요.', 'error')
      return false
    }
    const roleId = editingRole.id
    updateRoleMutation.mutate({ id: roleId, request }, {
      onSuccess: () => {
        setSelectedRoleId(roleId)
        setEditingRole(null)
        closeModal()
        showToast('역할 정보를 수정했어요.')
      },
      onError: (error) => {
        if (!isWorkspaceContentConflict(error)) return
        beginContentConflictRecovery('다른 구성원이 먼저 바꾼 최신 역할을 불러왔어요.')
      },
    })
    return true
  }

  const addRoleResource = (request: RoleResourceFormRequest) => {
    if (isRoleHandoffLocked(roleHandoffs, request.roleId)) {
      showToast('전달한 바통은 수락하거나 취소한 뒤 자료를 추가할 수 있어요.', 'error')
      return false
    }
    return roleResourceCreationCommand.submit(request, (createdResource) => {
      setSelectedRoleId(createdResource.roleId)
      closeModal()
      setView('roles')
      showToast('역할에 참고 자료를 연결했어요.')
    })
  }

  const updateExistingRoleResource = (request: RoleResourceFormRequest) => {
    if (!ensureFreshWorkspace()) return false
    if (!editingRoleResource) return false
    if (isRoleHandoffLocked(roleHandoffs, editingRoleResource.roleId)
      || isRoleHandoffLocked(roleHandoffs, request.roleId)) {
      showToast('전달한 바통은 수락하거나 취소한 뒤 자료를 수정할 수 있어요.', 'error')
      return false
    }
    updateRoleResourceMutation.mutate({ id: editingRoleResource.id, request }, {
      onSuccess: (updatedResource) => {
        setSelectedRoleId(updatedResource.roleId)
        closeModal()
        setView('roles')
        showToast('자료 링크를 수정했어요.')
      },
      onError: (error) => {
        if (!isWorkspaceContentConflict(error)) return
        beginContentConflictRecovery(
          '다른 구성원의 최신 자료를 불러왔어요. 내용을 확인한 뒤 다시 열어 주세요.',
        )
      },
    })
    return true
  }

  const addRoutine = (request: RoutineFormRequest) => {
    return routineCreationCommand.submit(request, () => {
      closeModal()
      setView('rhythm')
      showToast('반복 루틴을 운영 흐름에 추가했어요.')
    })
  }

  const updateExistingRoutine = (request: RoutineFormRequest) => {
    if (!ensureFreshWorkspace()) return false
    if (!editingRoutine) return false
    updateRoutineMutation.mutate({ id: editingRoutine.id, request }, {
      onSuccess: () => {
        setEditingRoutine(null)
        closeModal()
        setView('rhythm')
        showToast('루틴 정보를 수정했어요.')
      },
      onError: (error) => {
        if (!isWorkspaceContentConflict(error)) return
        beginContentConflictRecovery('다른 구성원이 먼저 바꾼 최신 루틴을 불러왔어요.')
      },
    })
    return true
  }

  const addSeasonRound = (request: CreateSeasonRoundRequest) => {
    return roundCreationCommand.submit(request, (createdRound) => {
      selectRound(createdRound.id)
      closeModal()
      setView('rhythm')
      showToast(`${createdRound.name} 운영 회차를 만들었어요.`)
    })
  }

  const updateExistingSeasonRound = (request: SeasonRoundFormRequest) => {
    if (!ensureFreshWorkspace()) return false
    if (!editingRound) return false
    const roundId = editingRound.id
    if (!beginRoundOperation(roundId)) return false
    void updateSeasonRoundMutation.mutateAsync({ id: roundId, request })
      .then(() => {
        selectRound(roundId)
        setEditingRound(null)
        closeModal()
        setView('rhythm')
        showToast('회차 정보를 수정했어요. 루틴 완료 기록은 그대로 유지됩니다.')
      })
      .catch((error: unknown) => {
        if (!isWorkspaceContentConflict(error)) return
        beginContentConflictRecovery('다른 구성원이 먼저 바꾼 최신 회차를 불러왔어요.')
      })
      .finally(() => endRoundOperation(roundId))
    return true
  }

  const updateSeasonRoundArchive = (round: SeasonRound, archived: boolean) => {
    if (!ensureFreshWorkspace()) return
    if (!beginRoundOperation(round.id)) return
    void seasonRoundArchiveMutation.mutateAsync({ id: round.id, archived })
      .then((updatedRound) => {
        if (archived) {
          setRoundSelection((current) => current.roundId === updatedRound.id
            ? { roundId: '', source: 'relevant-default' }
            : current)
          showToast('회차를 보관함으로 옮겼어요. 루틴 완료 기록은 그대로 유지됩니다.')
          return
        }
        selectRound(updatedRound.id)
        setView('rhythm')
        showToast('회차를 다시 운영 화면에 꺼냈어요.')
      })
      .catch((error: unknown) => {
        if (isWorkspaceContentConflict(error)) {
          beginContentConflictRecovery('다른 구성원의 최신 회차를 불러왔어요.')
          return
        }
        showToast(
          `회차를 ${archived ? '보관' : '복원'}하지 못했어요. ${mutationError(error)}`,
          'error',
        )
      })
      .finally(() => endRoundOperation(round.id))
  }

  const toggleRoutineExecution = (execution: RoutineExecution) => {
    if (!ensureFreshWorkspace()) return
    if (!selectedRound || execution.roundId !== selectedRound.id) return
    const roundId = selectedRound.id
    if (!beginRoundOperation(roundId)) return
    setRoundSelection({ roundId, source: 'user' })
    const completed = execution.status !== 'DONE'
    void routineExecutionCompletionMutation
      .mutateAsync({ roundId, executionId: execution.id, completed })
      .then(() => showToast(completed ? '이번 바통을 넘겼어요.' : '완료 표시를 되돌렸어요.'))
      .catch((error: unknown) => {
        if (isWorkspaceContentConflict(error)) {
          beginContentConflictRecovery('다른 구성원의 최신 회차 실행을 불러왔어요.')
          return
        }
        showToast(`완료 상태를 바꾸지 못했어요. ${mutationError(error)}`, 'error')
      })
      .finally(() => endRoundOperation(roundId))
  }

  const addDecision = (request: CreateDecisionRequest) => {
    return decisionCreationCommand.submit(request, () => {
      closeModal()
      setView('memory')
      showToast('결정과 이유를 팀의 기억에 남겼어요.')
    })
  }

  const updateExistingDecision = (request: DecisionFormRequest) => {
    if (!ensureFreshWorkspace()) return false
    if (!editingDecision) return false
    updateDecisionMutation.mutate({ id: editingDecision.id, request }, {
      onSuccess: () => {
        setEditingDecision(null)
        closeModal()
        showToast('결정 기록을 수정했어요.')
      },
      onError: (error) => {
        if (!isWorkspaceContentConflict(error)) return
        beginContentConflictRecovery('다른 구성원이 먼저 바꾼 최신 결정 기록을 불러왔어요.')
      },
    })
    return true
  }

  const updateDecisionArchive = (decision: Decision, archived: boolean) => {
    if (!ensureFreshWorkspace()) return
    if (decisionArchiveMutation.isPending) return
    decisionArchiveMutation.mutate({ id: decision.id, archived }, {
      onSuccess: () => showToast(
        archived ? '결정 기록을 보관함으로 옮겼어요.' : '결정 기록을 다시 원장에 꺼냈어요.',
      ),
      onError: (error) => {
        if (isWorkspaceContentConflict(error)) {
          beginContentConflictRecovery('다른 구성원의 최신 결정 기록을 불러왔어요.')
          return
        }
        showToast(`결정 기록을 ${archived ? '보관' : '복원'}하지 못했어요. ${mutationError(error)}`, 'error')
      },
    })
  }

  const addHandoffItem = (request: CreateHandoffItemRequest) => {
    if (isRoleHandoffLocked(roleHandoffs, request.roleId)) {
      showToast('전달한 바통은 수락하거나 취소한 뒤 항목을 추가할 수 있어요.', 'error')
      return false
    }
    return handoffItemCreationCommand.submit(request, (_createdItem, submittedRequest) => {
      setSelectedRoleId(submittedRequest.roleId)
      closeModal()
      setView('handoff')
      showToast('바통북에 새 항목을 추가했어요.')
    })
  }

  const updateExistingHandoffItem = (request: HandoffItemFormRequest) => {
    if (!ensureFreshWorkspace()) return false
    if (!editingHandoffItem) return false
    if (isRoleHandoffLocked(roleHandoffs, editingHandoffItem.roleId)
      || isRoleHandoffLocked(roleHandoffs, request.roleId)) {
      showToast('전달한 바통은 수락하거나 취소한 뒤 항목을 수정할 수 있어요.', 'error')
      return false
    }
    const itemId = editingHandoffItem.id
    if (!beginHandoffItemOperation(itemId)) return false
    void updateHandoffItemMutation.mutateAsync({ id: itemId, request })
      .then((updatedItem) => {
        setSelectedRoleId(updatedItem.roleId)
        setEditingHandoffItem(null)
        closeModal()
        showToast('바통북 항목을 수정했어요.')
      })
      .catch((error: unknown) => {
        if (!isWorkspaceContentConflict(error)) return
        beginContentConflictRecovery('다른 구성원이 먼저 바꾼 최신 바통 항목을 불러왔어요.')
      })
      .finally(() => endHandoffItemOperation(itemId))
    return true
  }

  const updateHandoffItemArchive = (item: HandoffItem, archived: boolean) => {
    if (!ensureFreshWorkspace()) return
    if (isRoleHandoffLocked(roleHandoffs, item.roleId)) {
      showToast('전달한 바통은 수락하거나 취소한 뒤 항목을 바꿀 수 있어요.', 'error')
      return
    }
    if (!beginHandoffItemOperation(item.id)) return
    void handoffItemArchiveMutation.mutateAsync({ id: item.id, archived })
      .then(() => showToast(
        archived ? '바통북 항목을 보관함으로 옮겼어요.' : '바통북 항목을 다시 체크리스트에 꺼냈어요.',
      ))
      .catch((error: unknown) => {
        if (isWorkspaceContentConflict(error)) {
          beginContentConflictRecovery('다른 구성원의 최신 바통 항목을 불러왔어요.')
          return
        }
        showToast(`바통 항목을 ${archived ? '보관' : '복원'}하지 못했어요. ${mutationError(error)}`, 'error')
      })
      .finally(() => endHandoffItemOperation(item.id))
  }

  const toggleHandoff = (id: string) => {
    if (!ensureFreshWorkspace()) return
    const item = activeHandoffItems.find((candidate) => candidate.id === id)
    if (item && isRoleHandoffLocked(roleHandoffs, item.roleId)) {
      showToast('전달한 바통은 수락하거나 취소한 뒤 완료 상태를 바꿀 수 있어요.', 'error')
      return
    }
    if (!item || !beginHandoffItemOperation(id)) return
    const completed = !item.completed
    void handoffCompletionMutation.mutateAsync({ id, completed })
      .then(() => showToast(completed ? '바통 항목을 준비했어요.' : '바통 항목을 다시 열었어요.'))
      .catch((error: unknown) => {
        if (isWorkspaceContentConflict(error)) {
          beginContentConflictRecovery('다른 구성원의 최신 바통 항목을 불러왔어요.')
          return
        }
        showToast(`바통 상태를 바꾸지 못했어요. ${mutationError(error)}`, 'error')
      })
      .finally(() => endHandoffItemOperation(id))
  }

  const prepareSelectedRoleHandoff = (request: PrepareRoleHandoffRequest) => {
    if (!roleHandoffActionRole || roleHandoffAction?.mode !== 'prepare') return false
    return prepareRoleHandoffCommand.submit({
      roleId: roleHandoffActionRole.id,
      ...request,
    }, (result) => {
      setSelectedRoleId(result.role.id)
      setRoleHandoffAction(null)
      closeModal()
      setView('handoff')
      showToast('다음 담당자와 기간을 정하고 역할 바통 준비를 시작했어요.')
    })
  }

  const transferSelectedRoleHandoff = (request: TransferRoleHandoffRequest) => {
    if (!roleHandoffActionRole
      || !roleHandoffActionTarget
      || roleHandoffAction?.mode !== 'transfer') return false
    transferRoleHandoffMutation.mutate({
      roleId: roleHandoffActionRole.id,
      handoffId: roleHandoffActionTarget.id,
      request,
    }, {
      onSuccess: () => {
        setRoleHandoffAction(null)
        closeModal()
        showToast('바통을 전달했어요. 다음 담당자의 수락을 기다립니다.')
      },
    })
    return true
  }

  const acceptSelectedRoleHandoff = (request: ConfirmRoleHandoffRequest) => {
    if (!roleHandoffActionRole
      || !roleHandoffActionTarget
      || roleHandoffAction?.mode !== 'accept') return false
    acceptRoleHandoffMutation.mutate({
      roleId: roleHandoffActionRole.id,
      handoffId: roleHandoffActionTarget.id,
      request,
    }, {
      onSuccess: () => {
        setRoleHandoffAction(null)
        closeModal()
        showToast('다음 담당자의 바통 수락과 역할 배정을 기록했어요.')
      },
    })
    return true
  }

  const cancelSelectedRoleHandoff = (request: CancelRoleHandoffRequest) => {
    if (!roleHandoffActionRole
      || !roleHandoffActionTarget
      || roleHandoffAction?.mode !== 'cancel') return false
    cancelRoleHandoffMutation.mutate({
      roleId: roleHandoffActionRole.id,
      handoffId: roleHandoffActionTarget.id,
      request,
    }, {
      onSuccess: () => {
        setRoleHandoffAction(null)
        closeModal()
        showToast('역할 바통을 취소하고 편집을 다시 열었어요.')
      },
    })
    return true
  }

  const workspaceInactive = Boolean(modal) || (inspectorOverlay && inspectorOpen)

  return (
    <>
      <div
        className={`app-shell ${selectedRole ? '' : 'no-inspector'}`}
        inert={workspaceInactive}
        aria-hidden={workspaceInactive || undefined}
      >
        <Sidebar workspace={activeWorkspace} calendarDate={calendarDate} view={view} onNavigate={openView} onSwitchSeason={openSeasonSwitcher} onShare={copyShareLink} onManageAccess={() => openModal('accessKey')} />

        <main className="main-surface" tabIndex={-1}>
          <MobileTopbar teamName={workspace.team.name} seasonName={workspace.season.name} onSwitchSeason={openSeasonSwitcher} onShare={copyShareLink} onManageAccess={() => openModal('accessKey')} />
        <div className="page-stage" key={view}>
          <WorkspaceSyncStatus
            updatedAt={workspaceQuery.dataUpdatedAt}
            syncing={workspaceQuery.isFetching}
            failed={workspaceQuery.isRefetchError}
            conflictRecoveryStatus={conflictRecoveryStatus}
            onRefresh={() => {
              if (conflictRecoveryStatus) {
                retryContentConflictRecovery()
                return
              }
              void workspaceQuery.refetch()
            }}
          />
          <SeasonEndedBanner
            season={workspace.season}
            onSwitchSeason={openSeasonSwitcher}
            onCreateNext={openSeasonSuccessor}
          />
          {view === 'today' && (
            <TodayView
              workspace={activeWorkspace}
              calendarLabel={calendarLabel}
              rounds={orderedActiveRounds}
              archivedRoundCount={orderedArchivedRounds.length}
              selectedRound={selectedRound}
              pendingCount={pendingCount}
              completedCount={completedCount}
              onSelectRound={selectRound}
              onAddRound={openRoundModal}
              onSelectRole={selectRole}
              onOpenDecision={openDecisionModal}
              onToggleRoutine={toggleRoutineExecution}
              onNavigate={openView}
              onOpenContinuitySignal={openContinuitySignal}
              onAddRole={openRoleModal}
              onAddRoutine={openRoutineModal}
              onEditRoutine={openRoutineEditModal}
              selectedRoundBusy={
                contentChangesDisabled
                || Boolean(selectedRound && busyRoundIds.has(selectedRound.id))
              }
              changesDisabled={contentChangesDisabled}
            />
          )}
          {view === 'roles' && (
            <RolesView
              roles={roles}
              roleHandoffs={roleHandoffs}
              members={members}
              selectedRoleId={effectiveSelectedRoleId}
              onSelectRole={selectRole}
              onManageMembers={openMemberManagementModal}
              onAddRole={openRoleModal}
              onEditRole={openRoleEditModal}
              handoffProgress={handoffProgress}
              changesDisabled={contentChangesDisabled}
            />
          )}
          {view === 'rhythm' && (
            <RhythmView
              season={workspace.season}
              roles={roles}
              routines={routines}
              rounds={orderedActiveRounds}
              archivedRounds={orderedArchivedRounds}
              selectedRound={selectedRound}
              members={members}
              onSelectRound={selectRound}
              onAddRound={openRoundModal}
              onEditRound={openRoundEditModal}
              onUpdateRoundArchive={updateSeasonRoundArchive}
              onSelectRole={selectRole}
              onToggleRoutine={toggleRoutineExecution}
              onAddRoutine={openRoutineModal}
              onAddRole={openRoleModal}
              onEditRoutine={openRoutineEditModal}
              onConfigureRoundSchedule={openRoundSchedule}
              busyRoundIds={busyRoundIds}
              changesDisabled={contentChangesDisabled}
            />
          )}
          {view === 'memory' && (
            <MemoryView
              decisions={activeDecisions}
              archivedDecisions={archivedDecisions}
              roles={roles}
              members={members}
              onOpenDecision={openDecisionModal}
              onAddRole={openRoleModal}
              onManageMembers={openMemberManagementModal}
              onSelectRole={selectRole}
              onEditDecision={openDecisionEditModal}
              onUpdateArchive={updateDecisionArchive}
              archivePending={decisionArchiveMutation.isPending}
              changesDisabled={contentChangesDisabled}
            />
          )}
          {view === 'handoff' && (
            <HandoffView
              roles={roles}
              roleHandoffs={roleHandoffs}
              members={members}
              season={workspace.season}
              calendarDate={calendarDate}
              selectedRoleId={effectiveSelectedRoleId}
              handoffItems={activeHandoffItems}
              archivedItems={archivedHandoffItems}
              onSelectRole={(id) => selectRole(id, false)}
              onToggle={toggleHandoff}
              onEditItem={openHandoffItemEditModal}
              onUpdateArchive={updateHandoffItemArchive}
              progress={handoffProgress}
              onPreview={() => openModal('handoffPreview')}
              onAddItem={openHandoffItemModal}
              onAddRole={openRoleModal}
              onPrepareHandoff={(role) => openRoleHandoffModal('prepare', role)}
              onTransferHandoff={(role, handoff) =>
                openRoleHandoffModal('transfer', role, handoff)}
              onAcceptHandoff={(role, handoff) =>
                openRoleHandoffModal('accept', role, handoff)}
              onCancelHandoff={(role, handoff) =>
                openRoleHandoffModal('cancel', role, handoff)}
              busyItemIds={busyHandoffItemIds}
              handoffTransitionPending={prepareRoleHandoffCommand.isPending
                || transferRoleHandoffMutation.isPending
                || acceptRoleHandoffMutation.isPending
                || cancelRoleHandoffMutation.isPending}
              changesDisabled={contentChangesDisabled}
            />
          )}
          {view === 'records' && (
            <RecordSearchView
              season={workspace.season}
              roles={roles}
              decisions={decisions}
              handoffItems={handoffItems}
              resources={resources}
              filters={recordSearchFilters}
              onFiltersChange={setRecordSearchFilters}
              onOpenResult={openRecordSearchResult}
            />
          )}
        </div>
        </main>

        {selectedRole && (
          <RoleInspector
            role={selectedRole}
            members={members}
            decisions={activeDecisions}
            routines={routines}
            resources={resources.filter((resource) => resource.roleId === selectedRole.id)}
            handoff={selectedRoleHandoff}
            progress={handoffProgress(selectedRole.id)}
            open={inspectorOpen}
            overlay={inspectorOverlay}
            blocked={Boolean(modal)}
            onClose={() => dismissInspector(true)}
            onAddResource={openRoleResourceModal}
            onEditResource={openRoleResourceEditModal}
            changesDisabled={contentChangesDisabled || selectedRoleLocked}
            onOpenHandoff={() => {
              setView('handoff')
              dismissInspector(false)
              window.requestAnimationFrame(() => {
                focusConnectedElement(document.querySelector<HTMLElement>('.main-surface'))
              })
            }}
          />
        )}

        <MobileNav view={view} onNavigate={openView} />
      </div>

      {modal === 'decision' && (
        <DecisionModal
          roles={roles}
          members={members}
          selectedRoleId={effectiveSelectedRoleId}
          decision={editingDecision ?? undefined}
          pending={editingDecision
            ? updateDecisionMutation.isPending
            : decisionCreationCommand.isPending}
          error={editingDecision ? updateDecisionMutation.error : decisionCreationCommand.error}
          storageError={editingDecision ? '' : decisionCreationCommand.storageError}
          recoveryAvailable={editingDecision ? false : hasPendingDecisionCreation}
          onClose={closeModal}
          onSave={editingDecision ? updateExistingDecision : addDecision}
        />
      )}
      {modal === 'members' && (
        <MemberManagementModal
          members={members}
          pendingMemberId={pendingMemberDeactivationId}
          error={updateMemberDeactivationMutation.error}
          changesDisabled={contentChangesDisabled}
          onAdd={openMemberModal}
          onEdit={openMemberEditModal}
          onToggleDeactivation={toggleMemberDeactivation}
          onClose={closeModal}
        />
      )}
      {modal === 'member' && (
        <MemberModal
          members={members}
          member={editingMember ?? undefined}
          pending={editingMember
            ? updateMemberMutation.isPending
            : memberCreationCommand.isPending}
          error={editingMember ? updateMemberMutation.error : memberCreationCommand.error}
          storageError={editingMember ? '' : memberCreationCommand.storageError}
          recoveryAvailable={editingMember ? false : hasPendingMemberCreation}
          onClose={() => {
            setEditingMember(null)
            closeModal()
          }}
          onCancel={returnToMemberManagement}
          onSave={editingMember ? updateExistingMember : addMember}
        />
      )}
      {modal === 'role' && (
        <RoleModal
          members={members}
          season={workspace.season}
          role={currentEditingRole}
          assignmentLocked={editingRoleAssignmentLocked}
          pending={editingRole
            ? updateRoleMutation.isPending
            : roleCreationCommand.isPending}
          error={editingRole ? updateRoleMutation.error : roleCreationCommand.error}
          storageError={editingRole ? '' : roleCreationCommand.storageError}
          recoveryAvailable={editingRole ? false : hasPendingRoleCreation}
          onClose={closeModal}
          onSave={editingRole ? updateExistingRole : addRole}
        />
      )}
      {modal === 'routine' && (
        <RoutineModal
          roles={roles}
          selectedRoleId={effectiveSelectedRoleId}
          routine={editingRoutine ?? undefined}
          pending={editingRoutine
            ? updateRoutineMutation.isPending
            : routineCreationCommand.isPending}
          error={editingRoutine ? updateRoutineMutation.error : routineCreationCommand.error}
          storageError={editingRoutine ? '' : routineCreationCommand.storageError}
          recoveryAvailable={editingRoutine ? false : hasPendingRoutineCreation}
          onClose={closeModal}
          onSave={editingRoutine ? updateExistingRoutine : addRoutine}
        />
      )}
      {modal === 'roundSchedule' && (
        <RoundScheduleModal
          season={workspace.season}
          pending={updateRoundScheduleMutation.isPending}
          error={updateRoundScheduleMutation.error}
          onClose={closeModal}
          onSave={saveRoundSchedule}
        />
      )}
      {modal === 'roleResource' && (
        <RoleResourceModal
          roles={roles}
          lockedRoleIds={lockedRoleIds}
          selectedRoleId={effectiveSelectedRoleId}
          resource={editingRoleResource ?? undefined}
          pending={editingRoleResource
            ? updateRoleResourceMutation.isPending
            : roleResourceCreationCommand.isPending}
          error={editingRoleResource
            ? updateRoleResourceMutation.error
            : roleResourceCreationCommand.error}
          storageError={editingRoleResource ? '' : roleResourceCreationCommand.storageError}
          recoveryAvailable={editingRoleResource ? false : hasPendingRoleResourceCreation}
          onClose={closeModal}
          onSave={editingRoleResource ? updateExistingRoleResource : addRoleResource}
        />
      )}
      {modal === 'round' && (
        <SeasonRoundModal
          season={workspace.season}
          roundCount={rounds.length}
          round={editingRound ?? undefined}
          pending={editingRound
            ? busyRoundIds.has(editingRound.id)
            : roundCreationCommand.isPending}
          error={editingRound ? updateSeasonRoundMutation.error : roundCreationCommand.error}
          storageError={editingRound ? '' : roundCreationCommand.storageError}
          recoveryAvailable={editingRound ? false : hasPendingRoundCreation}
          onClose={() => {
            setEditingRound(null)
            closeModal()
          }}
          onSave={editingRound ? updateExistingSeasonRound : addSeasonRound}
        />
      )}
      {modal === 'handoffItem' && (
        <HandoffItemModal
          roles={roles}
          lockedRoleIds={lockedRoleIds}
          selectedRoleId={effectiveSelectedRoleId}
          item={editingHandoffItem ?? undefined}
          pending={editingHandoffItem
            ? busyHandoffItemIds.has(editingHandoffItem.id)
            : handoffItemCreationCommand.isPending}
          error={editingHandoffItem
            ? updateHandoffItemMutation.error
            : handoffItemCreationCommand.error}
          storageError={editingHandoffItem ? '' : handoffItemCreationCommand.storageError}
          recoveryAvailable={editingHandoffItem ? false : hasPendingHandoffCreation}
          onClose={closeModal}
          onSave={editingHandoffItem ? updateExistingHandoffItem : addHandoffItem}
        />
      )}
      {modal === 'roleHandoff' && roleHandoffAction && roleHandoffActionRole && (
        <RoleHandoffModal
          key={`${roleHandoffAction.mode}:${roleHandoffActionRole.id}:${roleHandoffActionTarget?.id ?? 'new'}`}
          mode={roleHandoffAction.mode}
          role={roleHandoffActionRole}
          handoff={roleHandoffActionTarget}
          members={members}
          season={workspace.season}
          items={activeHandoffItems}
          resources={resources}
          pending={roleHandoffModalPending}
          error={roleHandoffModalError}
          storageError={roleHandoffAction.mode === 'prepare'
            ? prepareRoleHandoffCommand.storageError
            : ''}
          recoveryAvailable={roleHandoffAction.mode === 'prepare'
            && hasPendingRoleHandoffPreparation}
          onClose={() => {
            setRoleHandoffAction(null)
            closeModal()
          }}
          onPrepare={prepareSelectedRoleHandoff}
          onTransfer={transferSelectedRoleHandoff}
          onAccept={acceptSelectedRoleHandoff}
          onCancel={cancelSelectedRoleHandoff}
        />
      )}
      {modal === 'handoffPreview' && selectedRole && (
        <HandoffPreview
          role={selectedRole}
          members={members}
          routines={routines.filter((routine) => routine.ownerRoleId === selectedRole.id)}
          decisions={activeDecisions}
          resources={resources.filter((resource) => resource.roleId === selectedRole.id)}
          items={activeHandoffItems.filter((item) => item.roleId === selectedRole.id)}
          progress={handoffProgress(selectedRole.id)}
          onClose={closeModal}
        />
      )}
      {modal === 'shareLink' && <ShareLinkFallback shareUrl={shareUrl} onClose={closeModal} />}
      {modal === 'accessKey' && (
        <AccessKeyModal
          pending={rotationPending}
          error={rotationError}
          storageError={rotationStorageError}
          onClose={closeModal}
          onShare={copyShareLink}
          onRotate={rotateWorkspaceAccessKey}
        />
      )}
      {modal === 'seasonSwitcher' && (
        <SeasonSwitcherModal
          teamName={workspace.team.name}
          currentSeason={workspace.season}
          seasons={seasons}
          calendarDate={calendarDate}
          endingPending={updateSeasonEndingMutation.isPending}
          endingError={updateSeasonEndingMutation.error}
          onClose={closeModal}
          onSelect={selectSeason}
          onEdit={openSeasonEdit}
          onToggleEnding={toggleSeasonEnding}
          onCreateNext={openSeasonSuccessor}
        />
      )}
      {modal === 'seasonEdit' && (
        <SeasonEditModal
          season={workspace.season}
          pending={updateSeasonMutation.isPending}
          error={updateSeasonMutation.error}
          onClose={closeModal}
          onSave={saveSeason}
        />
      )}
      {modal === 'seasonSuccessor' && (
        <NextSeasonModal
          sourceSeason={workspace.season}
          roles={roles}
          routines={routines}
          pending={seasonSuccessorCommand.isPending}
          error={seasonSuccessorCommand.error}
          storageError={seasonSuccessorCommand.storageError}
          onClose={closeModal}
          onSave={createSuccessor}
        />
      )}

      {toast && (
        <div className={`toast ${toast.tone === 'error' ? 'toast-error' : ''}`} role="status">
          <Icon name={toast.tone === 'error' ? 'alert' : 'check'} size={16} />{toast.message}
        </div>
      )}
    </>
  )
}
