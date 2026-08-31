import {
  useCallback,
  useEffect,
  useEffectEvent,
  useLayoutEffect,
  useRef,
  useState,
  useSyncExternalStore,
} from 'react'
import type { ReactNode } from 'react'
import { ApiError } from '@/shared/api/ApiError'
import { Icon } from '@/shared/ui/Icon'
import { useAuthSession } from '@/features/auth/useAuthSession'
import { useWorkspaceConflictDraft, WorkspaceConflictDraft } from './WorkspaceConflictDraft'
import AccountMembershipPanel from '@/features/membership/AccountMembershipPanel'
import { PersonalWorkPanel } from './PersonalWorkPanel'
import { BriefEditionPanel } from '@/features/brief/BriefEditionPanel'
import {
  initialRecordSearchFilters,
  RecordSearchView,
} from './records/RecordSearchView'
import type {
  RecordSearchFilters,
  RecordSearchResult,
} from './records/recordSearch'
import type { WorkspaceScope } from './api'
import {
  useDecisionArchiveMutation,
  useHandoffCompletionMutation,
  useHandoffItemArchiveMutation,
  useRoutineArchiveMutation,
  useRoleResourceArchiveMutation,
  useRoutineExecutionCompletionMutation,
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
  SeasonSuccessorCleanupBanner,
  SeasonSwitcherModal,
} from './SeasonLifecycleModals'
import {
  useCreateDecisionCommand,
  useCreateHandoffItemCommand,
  useCreateMemberCommand,
  useCreateRoleCommand,
  useCreateRoleResourceCommand,
  useCreateRoutineCommand,
  useCreateSeasonRoundCommand,
  usePendingContentCreationCleanupCommand,
} from './useContentCreationCommand'
import {
  isWorkspaceAccessDenied,
  useWorkspaceAccessKeyFlow,
} from './useWorkspaceAccessKeyFlow'
import { useWorkspaceConflictRecovery } from './useWorkspaceConflictRecovery'
import { useWorkspaceMutationRecovery } from './useWorkspaceMutationRecovery'
import {
  DecisionModal,
  HandoffItemModal,
  MemberModal,
  MemberManagementModal,
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
  RoutineFormRequest,
} from './WorkspaceModals'
import {
  HandoffPreview,
  RoleHandoffModal,
} from './WorkspaceRoleHandoffModals'
import {
  useWorkspaceRoleHandoffFlow,
  type RoleHandoffModalMode,
} from './useWorkspaceRoleHandoffFlow'
import { useWorkspaceSeasonLifecycleFlow } from './useWorkspaceSeasonLifecycleFlow'
import { AccessKeyModal, ShareLinkFallback } from './WorkspaceAccessModals'
import {
  hasWorkspaceAccessKeyRecovery,
  WorkspaceAccessKeyRecovery,
} from './WorkspaceAccessKeyRecovery'
import {
  ContentCreationCleanupBanner,
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
  categoryCopy,
  phaseCopy,
  isActiveMember,
  isRoleHandoffLocked,
  latestRoleHandoff,
  mutationError,
} from './workspacePresentation'
import type {
  ContinuitySignal,
  CreateDecisionRequest,
  CreateHandoffItemRequest,
  CreateSeasonRoundRequest,
  Decision,
  HandoffItem,
  Member,
  Role,
  RoleHandoff,
  RoleResource,
  Routine,
  RoutineExecution,
  SeasonRound,
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

type WorkspaceAppProps = WorkspaceScope & {
  accessDeniedAction?: ReactNode
  onWorkspaceLoaded?: (workspace: WorkspaceProjection) => void
  onSelectSeason: (seasonId: string, accessKey: string) => void
  onSeasonCreated: (seasonId: string, accessKey: string) => void
}

function useMediaQuery(query: string, onBeforeChange?: (matches: boolean) => void) {
  const notifyBeforeChange = useEffectEvent((matches: boolean) => {
    onBeforeChange?.(matches)
  })
  const subscribe = useCallback((notify: () => void) => {
    const mediaQuery = window.matchMedia(query)
    const updateMatches = (event: MediaQueryListEvent) => {
      notifyBeforeChange(event.matches)
      notify()
    }
    mediaQuery.addEventListener('change', updateMatches)
    return () => mediaQuery.removeEventListener('change', updateMatches)
  }, [query])
  const getSnapshot = useCallback(() => window.matchMedia(query).matches, [query])

  return useSyncExternalStore(subscribe, getSnapshot, () => false)
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
  const [currentAccessKey, setCurrentAccessKey] = useState(accessKey)
  const scope = { teamId, seasonId, accessKey: currentAccessKey }
  const workspaceQuery = useWorkspaceQuery(scope)
  const sessionQuery = useAuthSession()
  const conflictDraftFlow = useWorkspaceConflictDraft(JSON.stringify([
    teamId, seasonId, currentAccessKey,
    sessionQuery.data?.authenticated ? sessionQuery.data.accountId : 'anonymous',
    workspaceQuery.error instanceof ApiError && workspaceQuery.error.code === 'WORKSPACE_ACCESS_DENIED',
  ]))
  const preserveConflictDraft = conflictDraftFlow.preserve
  const contentCreationCleanupCommand = usePendingContentCreationCleanupCommand()
  const memberCreationCommand = useCreateMemberCommand(scope)
  const updateMemberMutation = useUpdateMemberMutation(scope)
  const updateMemberDeactivationMutation = useUpdateMemberDeactivationMutation(scope)
  const roleCreationCommand = useCreateRoleCommand(scope)
  const updateRoleMutation = useUpdateRoleMutation(scope)
  const roleResourceCreationCommand = useCreateRoleResourceCommand(scope)
  const updateRoleResourceMutation = useUpdateRoleResourceMutation(scope)
  const roleResourceArchiveMutation = useRoleResourceArchiveMutation(scope)
  const routineCreationCommand = useCreateRoutineCommand(scope)
  const updateRoutineMutation = useUpdateRoutineMutation(scope)
  const routineArchiveMutation = useRoutineArchiveMutation(scope)
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
  const seasonLifecycleFlow = useWorkspaceSeasonLifecycleFlow({
    scope,
    workspace: workspaceQuery.data,
    currentAccessKey,
    onOpenModal: openModal,
    onCloseModal: closeModal,
    onSelectSeason,
    onSeasonCreated,
    notify: showToast,
    preserveConflictDraft,
  })
  const retryContentCreationCleanup = () => {
    const retry = contentCreationCleanupCommand.retryCleanup()
    if (!retry) return
    void retry.then((completed) => {
      if (completed) showToast('이전 콘텐츠 생성의 완료 기록을 정리했어요.')
    })
  }
  const roleHandoffFlow = useWorkspaceRoleHandoffFlow({
    scope,
    roles: workspaceQuery.data?.roles ?? [],
    roleHandoffs: workspaceQuery.data?.roleHandoffs ?? [],
    modalOpen: modal === 'roleHandoff',
    onOpenModal: () => openModal('roleHandoff'),
    onCloseModal: closeModal,
    onSelectRole: setSelectedRoleId,
    onOpenHandoffView: () => setView('handoff'),
    notify: showToast,
  })
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
    busyIds: busyRoutineIds,
    begin: beginRoutineOperation,
    end: endRoutineOperation,
  } = useRecordBusyIds()
  const routineArchiveFocusRef = useRef<{ routineId: string; archived: boolean } | null>(null)

  useEffect(() => {
    const focusRequest = routineArchiveFocusRef.current
    if (!focusRequest || busyRoutineIds.has(focusRequest.routineId)) return

    const target = focusRequest.archived
      ? document.querySelector<HTMLElement>('.routine-archive-shelf > summary')
      : [...document.querySelectorAll<HTMLElement>('.routine-row')]
          .find((row) => row.dataset.routineId === focusRequest.routineId)
          ?.querySelector<HTMLElement>('.routine-archive-button') ?? null
    if (!canReceiveFocus(target)) return

    focusConnectedElement(target)
    if (document.activeElement === target) routineArchiveFocusRef.current = null
  }, [busyRoutineIds, workspaceQuery.data?.routines])

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
  const discardWorkspaceEditors = () => {
    setEditingMember(null)
    setEditingRole(null)
    setEditingRoleResource(null)
    setEditingRoutine(null)
    setEditingRound(null)
    setEditingDecision(null)
    setEditingHandoffItem(null)
    roleHandoffFlow.discard()
    closeModal()
  }
  const {
    recoveryStatus: conflictRecoveryStatus,
    beginRecovery: beginContentConflictRecovery,
    retryRecovery: retryContentConflictRecovery,
    ensureFreshWorkspace,
  } = useWorkspaceConflictRecovery({
    scopeKey: JSON.stringify([teamId, seasonId, currentAccessKey]),
    refetchWorkspace: () => workspaceQuery.refetch({ throwOnError: true }),
    discardEditors: discardWorkspaceEditors,
    notify: showToast,
  })
  useWorkspaceMutationRecovery({
    teamId,
    seasonId,
    onRoleHandoffConflict: () => beginContentConflictRecovery(
      '다른 구성원이 먼저 바꾼 최신 역할 바통 상태를 불러왔어요.',
    ),
    onWorkspaceContentConflict: () => beginContentConflictRecovery(
      '다른 구성원이 먼저 바꾼 최신 작업 공간을 불러왔어요.',
    ),
    onSeasonEnded: () => {
      discardWorkspaceEditors()
      showToast(
        '다른 구성원이 시즌을 종료했어요. 최신 기록을 읽기 전용으로 다시 불러옵니다.',
        'error',
      )
      void workspaceQuery.refetch()
    },
  })

  useEffect(() => {
    if (workspaceQuery.data) onWorkspaceLoaded?.(workspaceQuery.data)
  }, [onWorkspaceLoaded, workspaceQuery.data])

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
    roleHandoffs,
    members,
  } = workspace
  const seasons = workspace.seasons
  const activeMembers = members.filter(isActiveMember)
  const seasonEnded = Boolean(workspace.season.endedAt)
  const contentChangesDisabled = seasonEnded || Boolean(conflictRecoveryStatus)
  const activeRoutines = routines.filter((routine) => !routine.archivedAt)
  const archivedRoutines = [...routines]
    .filter((routine) => routine.archivedAt)
    .sort((left, right) => (right.archivedAt ?? '').localeCompare(left.archivedAt ?? ''))
  const activeRounds = rounds.filter((round) => !round.archivedAt)
  const archivedRounds = rounds.filter((round) => round.archivedAt)
  const activeDecisions = decisions.filter((decision) => !decision.archivedAt)
  const archivedDecisions = decisions.filter((decision) => decision.archivedAt)
  const activeHandoffItems = handoffItems.filter((item) => !item.archivedAt)
  const archivedHandoffItems = handoffItems.filter((item) => item.archivedAt)
  const activeWorkspace = {
    ...workspace,
    routines: activeRoutines,
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
  const selectedRoleLocked = selectedRoleHandoff?.status === 'TRANSFERRED'
  const lockedRoleIds = new Set(
    roleHandoffs
      .filter((handoff) => handoff.status === 'TRANSFERRED')
      .map((handoff) => handoff.roleId),
  )
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
  const hasPendingRoleResourceCreation = modal === 'roleResource'
    && !editingRoleResource
    && roleResourceCreationCommand.hasPending()
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
    let itemCount = 0
    let completedItemCount = 0
    for (const item of activeHandoffItems) {
      if (item.roleId !== roleId) continue
      itemCount += 1
      if (item.completed) completedItemCount += 1
    }
    if (!itemCount) return 0
    return Math.round((completedItemCount / itemCount) * 100)
  }

  const openRoleModal = () => {
    setEditingRole(null)
    roleCreationCommand.reset()
    openModal('role')
  }

  const openMemberManagementModal = () => {
    if (conflictRecoveryStatus) return
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
    if (!activeRoutines.length) {
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
    if (routine.archivedAt) {
      showToast('보관한 루틴은 복원한 뒤 수정해 주세요.', 'error')
      return
    }
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
    roleHandoffFlow.open(mode, role, handoff)
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
    return preserveConflictDraft(updateMemberMutation.mutateAsync({ id: editingMember.id, request }, {
      onSuccess: (updatedMember) => {
        setEditingMember(null)
        openModal('members')
        showToast(`${updatedMember.name}님의 표시 이름을 수정했어요.`)
      },
    }), '구성원 이름 수정', [['구성원 이름', request.name]])
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
        if (isWorkspaceContentConflict(error)) return
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
    return preserveConflictDraft(updateRoleMutation.mutateAsync({ id: roleId, request }, {
      onSuccess: () => {
        setSelectedRoleId(roleId)
        setEditingRole(null)
        closeModal()
        showToast('역할 정보를 수정했어요.')
      },
    }), '역할 수정', [
      ['역할 이름', request.name], ['역할의 목적', request.purpose],
      ['현재 담당자', members.find((member) => member.id === request.currentMemberId)?.name],
      ['다음 담당자', members.find((member) => member.id === request.nextMemberId)?.name],
      ['담당 시작일', request.assignmentStartDate], ['담당 종료일', request.assignmentEndDate],
      ['핵심 책임', request.responsibilities.join('\n')], ['위험 신호', request.risk],
    ])
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
    return preserveConflictDraft(updateRoleResourceMutation.mutateAsync({ id: editingRoleResource.id, request }, {
      onSuccess: (updatedResource) => {
        setSelectedRoleId(updatedResource.roleId)
        closeModal()
        setView('roles')
        showToast('자료 링크를 수정했어요.')
      },
    }), '참고 자료 수정', [
      ['역할', roles.find((role) => role.id === request.roleId)?.name],
      ['자료 이름', request.title], ['자료 주소', request.url], ['설명', request.description],
    ])
  }

  const updateRoleResourceArchive = (resource: RoleResource, archived: boolean) => {
    if (!ensureFreshWorkspace()) return
    if (isRoleHandoffLocked(roleHandoffs, resource.roleId)) {
      showToast('전달한 바통은 수락하거나 취소한 뒤 자료를 보관하거나 복원할 수 있어요.', 'error')
      return
    }
    roleResourceArchiveMutation.mutate({ id: resource.id, archived }, {
      onSuccess: () => showToast(archived ? '자료를 보관함으로 옮겼어요.' : '자료를 다시 연결했어요.'),
      onError: (error) => {
        if (isWorkspaceContentConflict(error)) return
        showToast(`자료 상태를 바꾸지 못했어요. ${mutationError(error)}`, 'error')
      },
    })
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
    return preserveConflictDraft(updateRoutineMutation.mutateAsync({ id: editingRoutine.id, request }, {
      onSuccess: () => {
        setEditingRoutine(null)
        closeModal()
        setView('rhythm')
        showToast('루틴 정보를 수정했어요.')
      },
    }), '루틴 수정', [
      ['루틴 이름', request.title], ['운영 단계', phaseCopy[request.phase]],
      ['언제까지', request.dueLabel], ['세부 설명', request.detail],
      ['담당 역할', roles.find((role) => role.id === request.ownerRoleId)?.name],
      ['모임일 기준 마감일 차이', request.deadlineDayOffset], ['마감 시각', request.deadlineTime],
    ])
  }

  const focusRoutineArchiveResult = (routineId: string, archived: boolean) => {
    routineArchiveFocusRef.current = { routineId, archived }
  }

  const updateRoutineArchive = (routine: Routine, archived: boolean) => {
    if (!ensureFreshWorkspace()) return
    if (!beginRoutineOperation(routine.id)) return
    void routineArchiveMutation.mutateAsync({ id: routine.id, archived })
      .then((updatedRoutine) => {
        setView('rhythm')
        showToast(archived
          ? '루틴 정의를 보관했어요. 이미 만든 회차의 실행 기록은 그대로 유지됩니다.'
          : '루틴을 다시 운영 흐름에 꺼냈어요. 새 회차부터 포함됩니다.')
        focusRoutineArchiveResult(updatedRoutine.id, archived)
      })
      .catch((error: unknown) => {
        if (isWorkspaceContentConflict(error)) return
        showToast(
          `루틴을 ${archived ? '보관' : '복원'}하지 못했어요. ${mutationError(error)}`,
          'error',
        )
      })
      .finally(() => endRoutineOperation(routine.id))
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
    return preserveConflictDraft(updateSeasonRoundMutation.mutateAsync({ id: roundId, request }), '회차 정보 수정', [
      ['회차 이름', request.name], ['모임 날짜', request.meetingDate],
    ])
      .then(() => {
        selectRound(roundId)
        setEditingRound(null)
        closeModal()
        setView('rhythm')
        showToast('회차 정보를 수정했어요. 루틴 완료 기록은 그대로 유지됩니다.')
      })
      .catch(() => undefined)
      .finally(() => endRoundOperation(roundId))
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
        if (isWorkspaceContentConflict(error)) return
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
        if (isWorkspaceContentConflict(error)) return
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
    return preserveConflictDraft(updateDecisionMutation.mutateAsync({ id: editingDecision.id, request }, {
      onSuccess: () => {
        setEditingDecision(null)
        closeModal()
        showToast('결정 기록을 수정했어요.')
      },
    }), '결정 기록 수정', [
      ['결정', request.title], ['선택 이유', request.reason], ['검토한 대안', request.alternative],
      ['작성자', members.find((member) => member.id === request.authorMemberId)?.name],
      ['관련 역할', roles.filter((role) => request.roleIds.includes(role.id)).map((role) => role.name).join('\n')],
    ])
  }

  const updateDecisionArchive = (decision: Decision, archived: boolean) => {
    if (!ensureFreshWorkspace()) return
    if (decisionArchiveMutation.isPending) return
    decisionArchiveMutation.mutate({ id: decision.id, archived }, {
      onSuccess: () => showToast(
        archived ? '결정 기록을 보관함으로 옮겼어요.' : '결정 기록을 다시 원장에 꺼냈어요.',
      ),
      onError: (error) => {
        if (isWorkspaceContentConflict(error)) return
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
    return preserveConflictDraft(updateHandoffItemMutation.mutateAsync({ id: itemId, request }), '바통북 항목 수정', [
      ['역할', roles.find((role) => role.id === request.roleId)?.name],
      ['남길 내용', request.label], ['항목 종류', categoryCopy[request.category]],
    ])
      .then((updatedItem) => {
        setSelectedRoleId(updatedItem.roleId)
        setEditingHandoffItem(null)
        closeModal()
        showToast('바통북 항목을 수정했어요.')
      })
      .catch(() => undefined)
      .finally(() => endHandoffItemOperation(itemId))
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
        if (isWorkspaceContentConflict(error)) return
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
        if (isWorkspaceContentConflict(error)) return
        showToast(`바통 상태를 바꾸지 못했어요. ${mutationError(error)}`, 'error')
      })
      .finally(() => endHandoffItemOperation(id))
  }

  const workspaceInactive = inspectorOverlay && inspectorOpen

  return (
    <>
      <div
        className={`app-shell ${selectedRole ? '' : 'no-inspector'}`}
        inert={workspaceInactive}
        aria-hidden={workspaceInactive || undefined}
      >
        <Sidebar workspace={activeWorkspace} calendarDate={calendarDate} view={view} onNavigate={openView} onSwitchSeason={seasonLifecycleFlow.actions.openSwitcher} onShare={copyShareLink} onManageAccess={() => openModal('accessKey')} />

        <main className="main-surface" tabIndex={-1}>
          <MobileTopbar teamName={workspace.team.name} seasonName={workspace.season.name} onSwitchSeason={seasonLifecycleFlow.actions.openSwitcher} onShare={copyShareLink} onManageAccess={() => openModal('accessKey')} />
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
          {conflictDraftFlow.draft && (
            <WorkspaceConflictDraft
              key={conflictDraftFlow.draft.text}
              draft={conflictDraftFlow.draft}
              onDiscard={conflictDraftFlow.discard}
            />
          )}
          {contentCreationCleanupCommand.cleanupRequired && (
            <ContentCreationCleanupBanner
              message={contentCreationCleanupCommand.message}
              pending={contentCreationCleanupCommand.pending}
              onRetry={retryContentCreationCleanup}
            />
          )}
          {seasonLifecycleFlow.cleanup.visible && (
            <SeasonSuccessorCleanupBanner
              pending={seasonLifecycleFlow.cleanup.pending}
              onRetry={seasonLifecycleFlow.actions.retrySuccessorCleanup}
            />
          )}
          <SeasonEndedBanner
            season={workspace.season}
            onSwitchSeason={seasonLifecycleFlow.actions.openSwitcher}
            onCreateNext={seasonLifecycleFlow.actions.openSuccessor}
          />
          {view === 'today' && (
            <TodayView
              workspace={activeWorkspace}
              personalWork={<PersonalWorkPanel
                workspace={workspace}
                accessKey={currentAccessKey}
                onManageMembership={openMemberManagementModal}
                onOpenRound={(roundId, executionId) => {
                  selectRound(roundId)
                  openView('rhythm')
                  window.requestAnimationFrame(() => {
                    const target = document.querySelector<HTMLElement>(
                      `[data-execution-id="${executionId}"] .routine-copy`,
                    )
                    target?.scrollIntoView({ block: 'center' })
                    focusConnectedElement(target)
                  })
                }}
                onOpenHandoff={(roleId) => {
                  setSelectedRoleId(roleId)
                  openView('handoff')
                  window.requestAnimationFrame(() => {
                    const target = document.querySelector<HTMLElement>('.handoff-workspace')
                    target?.scrollIntoView({ block: 'center' })
                    focusConnectedElement(target)
                  })
                }}
              />}
              weeklyBrief={<BriefEditionPanel
                workspace={workspace}
                accessKey={currentAccessKey}
                changesDisabled={contentChangesDisabled}
                onManageMembership={openMemberManagementModal}
              />}
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
              selectedRoundOperationPending={Boolean(
                selectedRound && busyRoundIds.has(selectedRound.id),
              )}
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
              memberManagementDisabled={Boolean(conflictRecoveryStatus)}
            />
          )}
          {view === 'rhythm' && (
            <RhythmView
              season={workspace.season}
              roles={roles}
              routines={activeRoutines}
              archivedRoutines={archivedRoutines}
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
              onUpdateRoutineArchive={updateRoutineArchive}
              onConfigureRoundSchedule={seasonLifecycleFlow.actions.openRoundSchedule}
              busyRoundIds={busyRoundIds}
              busyRoutineIds={busyRoutineIds}
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
              handoffTransitionPending={roleHandoffFlow.transitionPending}
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
            routines={activeRoutines}
            resources={resources.filter((resource) => resource.roleId === selectedRole.id)}
            handoff={selectedRoleHandoff}
            progress={handoffProgress(selectedRole.id)}
            open={inspectorOpen}
            overlay={inspectorOverlay}
            blocked={Boolean(modal)}
            onClose={() => dismissInspector(true)}
            onAddResource={openRoleResourceModal}
            onEditResource={openRoleResourceEditModal}
            onUpdateResourceArchive={updateRoleResourceArchive}
            onManageMembership={openMemberManagementModal}
            roundRoomScope={scope}
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
          accountMembershipPanel={(
            <AccountMembershipPanel
              teamId={teamId}
              seasonId={seasonId}
              accessKey={currentAccessKey}
              members={members}
              changesDisabled={contentChangesDisabled}
              seasonEnded={seasonEnded}
            />
          )}
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
          pending={seasonLifecycleFlow.roundSchedule.pending}
          error={seasonLifecycleFlow.roundSchedule.error}
          onClose={closeModal}
          onSave={seasonLifecycleFlow.actions.saveRoundSchedule}
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
      {modal === 'roleHandoff' && roleHandoffFlow.action && roleHandoffFlow.actionRole && (
        <RoleHandoffModal
          key={`${roleHandoffFlow.action.mode}:${roleHandoffFlow.actionRole.id}:${roleHandoffFlow.actionTarget?.id ?? 'new'}`}
          mode={roleHandoffFlow.action.mode}
          role={roleHandoffFlow.actionRole}
          handoff={roleHandoffFlow.actionTarget}
          members={members}
          season={workspace.season}
          items={activeHandoffItems}
          resources={resources.filter((resource) => !resource.archivedAt)}
          pending={roleHandoffFlow.modalPending}
          error={roleHandoffFlow.modalError}
          storageError={roleHandoffFlow.modalStorageError}
          recoveryAvailable={roleHandoffFlow.recoveryAvailable}
          onClose={roleHandoffFlow.close}
          onPrepare={roleHandoffFlow.prepare}
          onTransfer={roleHandoffFlow.transfer}
          onAccept={roleHandoffFlow.accept}
          onCancel={roleHandoffFlow.cancel}
        />
      )}
      {modal === 'handoffPreview' && selectedRole && (
        <HandoffPreview
          role={selectedRole}
          workspaceLabel={`${workspace.team.name} · ${workspace.season.name}`}
          members={members}
          routines={activeRoutines.filter((routine) => routine.ownerRoleId === selectedRole.id)}
          decisions={activeDecisions}
          resources={resources.filter(
            (resource) => resource.roleId === selectedRole.id && !resource.archivedAt,
          )}
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
          endingPending={seasonLifecycleFlow.switcher.endingPending}
          endingError={seasonLifecycleFlow.switcher.endingError}
          onClose={closeModal}
          onSelect={seasonLifecycleFlow.actions.selectSeason}
          onEdit={seasonLifecycleFlow.actions.openEdit}
          onToggleEnding={seasonLifecycleFlow.actions.toggleEnding}
          onCreateNext={seasonLifecycleFlow.actions.openSuccessor}
        />
      )}
      {modal === 'seasonEdit' && (
        <SeasonEditModal
          season={workspace.season}
          pending={seasonLifecycleFlow.edit.pending}
          error={seasonLifecycleFlow.edit.error}
          onClose={closeModal}
          onSave={seasonLifecycleFlow.actions.saveSeason}
        />
      )}
      {modal === 'seasonSuccessor' && (
        <NextSeasonModal
          sourceSeason={workspace.season}
          roles={roles}
          routines={activeRoutines}
          cleanupRequired={seasonLifecycleFlow.successor.cleanupRequired}
          pending={seasonLifecycleFlow.successor.pending}
          error={seasonLifecycleFlow.successor.error}
          storageError={seasonLifecycleFlow.successor.storageError}
          onClose={closeModal}
          onSave={seasonLifecycleFlow.actions.createSuccessor}
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
