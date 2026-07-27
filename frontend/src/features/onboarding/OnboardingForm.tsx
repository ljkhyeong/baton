import {
  useCallback,
  useEffect,
  useRef,
  useState,
} from 'react'
import type { FormEvent } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router-dom'
import { createWorkspace, saveAccessKey } from '@/features/workspace/api'
import { ApiClientError, ApiError } from '@/shared/api/ApiError'
import {
  forgetRecentWorkspace,
  readRecentWorkspaces,
  subscribeRecentWorkspaces,
} from '@/features/workspace/storage'
import type { RecentWorkspace } from '@/features/workspace/storage'
import type { CreateWorkspaceRequest } from '@/features/workspace/types'
import PendingWorkspaceCreationPanel from './PendingWorkspaceCreationPanel'
import {
  clearPendingWorkspaceCreation,
  isPendingWorkspaceCreationRequest,
  isSameWorkspaceCreationRequest,
  listPendingWorkspaceCreations,
  preparePendingWorkspaceRecovery,
  prepareWorkspaceCreation,
  runWithWorkspaceCreationLock,
  subscribePendingWorkspaceCreations,
} from './pendingWorkspaceCreation'
import type {
  PendingWorkspaceCreationItem,
  PendingWorkspaceCreationListResult,
} from './pendingWorkspaceCreation'
import {
  MAX_INITIAL_MEMBER_COUNT,
  MAX_MEMBER_NAME_LENGTH,
  MAX_WORKSPACE_NAME_LENGTH,
} from './workspaceCreationConstraints'

function splitMemberNames(value: string) {
  return value.split(/[\n,]/).map((name) => name.trim()).filter(Boolean)
}

function errorMessage(error: unknown) {
  if (error instanceof ApiError && error.code === 'IDEMPOTENCY_REPLAY_EXPIRED') {
    return '생성 뒤 접근 키가 변경되어 이전 결과를 다시 받을 수 없습니다. 작업 공간이 이미 만들어졌을 수 있으니 운영자나 기존 공유 링크를 먼저 확인해 주세요.'
  }
  if (error instanceof ApiError && error.code === 'IDEMPOTENCY_KEY_REUSED') {
    return '이전 생성 요청 키가 다른 요청에 사용됐습니다. 입력을 확인한 뒤 새 요청으로 다시 시도해 주세요.'
  }
  if (error instanceof ApiError || error instanceof ApiClientError) return error.message
  return '작업 공간을 만들지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

function shouldDiscardPendingCreation(error: unknown) {
  return error instanceof ApiError
    && (
      error.code === 'INVALID_INPUT'
      || error.code === 'IDEMPOTENCY_KEY_REUSED'
      || error.code === 'IDEMPOTENCY_REPLAY_EXPIRED'
    )
}

const pendingStorageRequiredMessage = '요청을 안전하게 저장할 수 없습니다. 시크릿 창이 아닌 일반 브라우저 창에서 열거나 브라우저 저장을 허용한 뒤 다시 시도해 주세요.'
const pendingCreationLimitMessage = '확인하지 못한 생성 요청이 5개 남아 새 요청을 저장할 수 없습니다. 아래에서 같은 요청의 결과를 확인하거나, 이미 확인한 복구 기록을 폐기한 뒤 다시 시도해 주세요.'
const creationBusyMessage = '다른 탭에서 작업 공간 생성 결과를 확인 중입니다. 처리가 끝난 뒤 다시 시도해 주세요.'
const creationLockUnsupportedMessage = '이 브라우저에서는 탭 사이의 생성 요청을 안전하게 조정할 수 없습니다. 브라우저를 최신 버전으로 업데이트하거나 다른 브라우저에서 다시 열어 주세요.'

function formatLastOpenedAt(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return new Intl.DateTimeFormat('ko-KR', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

type CreateWorkspaceVariables = {
  request: CreateWorkspaceRequest
  idempotencyKey: string
  creationKey?: string
}

export default function OnboardingForm() {
  const navigate = useNavigate()
  const teamNameInputRef = useRef<HTMLInputElement>(null)
  const [teamName, setTeamName] = useState('')
  const [seasonName, setSeasonName] = useState('')
  const [startDate, setStartDate] = useState('')
  const [endDate, setEndDate] = useState('')
  const [memberNamesInput, setMemberNamesInput] = useState('')
  const [creationKey, setCreationKey] = useState('')
  const [validationMessage, setValidationMessage] = useState('')
  const [creationAttemptPending, setCreationAttemptPending] = useState(false)
  const [uncertainCreationDraft, setUncertainCreationDraft] =
    useState<CreateWorkspaceRequest | null>(null)
  const [selectedPendingCreation, setSelectedPendingCreation] =
    useState<PendingWorkspaceCreationItem | null>(null)
  const [pendingCreationList, setPendingCreationList] =
    useState<PendingWorkspaceCreationListResult>({ status: 'ready', items: [] })
  const [recentWorkspaces, setRecentWorkspaces] = useState<RecentWorkspace[]>(readRecentWorkspaces)

  const createMutation = useMutation({
    mutationFn: ({ request, idempotencyKey, creationKey: operatorKey }: CreateWorkspaceVariables) =>
      createWorkspace(request, { idempotencyKey, creationKey: operatorKey }),
  })

  const refreshPendingCreations = useCallback(() => {
    setPendingCreationList(listPendingWorkspaceCreations())
  }, [])

  useEffect(() => {
    refreshPendingCreations()
    return subscribePendingWorkspaceCreations(refreshPendingCreations)
  }, [refreshPendingCreations])

  useEffect(() => {
    const refreshRecentWorkspaces = () => setRecentWorkspaces(readRecentWorkspaces())
    return subscribeRecentWorkspaces(refreshRecentWorkspaces)
  }, [])

  const currentDraftRequest: CreateWorkspaceRequest = {
    teamName,
    seasonName,
    startDate,
    endDate,
    memberNames: splitMemberNames(memberNamesInput),
  }
  const selectedPendingMatchesDraft = selectedPendingCreation !== null
    && isPendingWorkspaceCreationRequest(selectedPendingCreation, currentDraftRequest)
  const pendingCreations = pendingCreationList.status === 'ready'
    ? pendingCreationList.items
    : []
  const selectedPendingKnownMissing = selectedPendingMatchesDraft
    && pendingCreationList.status === 'ready'
    && !pendingCreationList.items.some((item) =>
      item.idempotencyKey === selectedPendingCreation.idempotencyKey
      && item.createdAt === selectedPendingCreation.createdAt)
  const uncertainCreationMatchesDraft = uncertainCreationDraft !== null
    && isSameWorkspaceCreationRequest(uncertainCreationDraft, currentDraftRequest)
  const creationConfirmationReason = selectedPendingKnownMissing
    ? 'pendingMissing'
    : uncertainCreationMatchesDraft
      ? 'concurrentAttempt'
      : null
  const creationBusy = creationAttemptPending || createMutation.isPending
  const creationSubmitLabel = creationBusy
    ? '작업 공간 확인하는 중…'
    : creationConfirmationReason
      ? '기존 결과 확인 필요'
      : selectedPendingMatchesDraft
        ? '같은 생성 결과 확인하기'
        : '작업 공간 만들기'

  useEffect(() => {
    if (!uncertainCreationDraft || pendingCreationList.status !== 'ready') return
    const matchingPending = pendingCreationList.items.find((item) =>
      isSameWorkspaceCreationRequest(item.request, uncertainCreationDraft))
    if (!matchingPending) return

    setSelectedPendingCreation(matchingPending)
    setUncertainCreationDraft(null)
  }, [pendingCreationList, uncertainCreationDraft])

  useEffect(() => {
    if (!selectedPendingKnownMissing) return
    setValidationMessage((current) => current === creationBusyMessage ? '' : current)
  }, [selectedPendingKnownMissing])

  const attemptCreation = async (variables: CreateWorkspaceVariables) => {
    try {
      const { teamId, seasonId, accessKey } = await createMutation.mutateAsync(variables)
      const workspacePath = `/teams/${encodeURIComponent(teamId)}/seasons/${encodeURIComponent(seasonId)}`
      const saved = saveAccessKey(teamId, accessKey)
      clearPendingWorkspaceCreation(variables.request, variables.idempotencyKey)
      refreshPendingCreations()
      navigate(saved ? workspacePath : `${workspacePath}#accessKey=${encodeURIComponent(accessKey)}`)
    } catch (error) {
      if (shouldDiscardPendingCreation(error)) {
        clearPendingWorkspaceCreation(variables.request, variables.idempotencyKey)
      }
      refreshPendingCreations()
    }
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (creationBusy) return
    setValidationMessage('')
    createMutation.reset()

    const normalizedTeamName = teamName.trim()
    const normalizedSeasonName = seasonName.trim()
    const memberNames = splitMemberNames(memberNamesInput)
    if (!normalizedTeamName) {
      setValidationMessage('팀 이름을 입력해 주세요.')
      return
    }
    if (normalizedTeamName.length > MAX_WORKSPACE_NAME_LENGTH) {
      setValidationMessage(`팀 이름은 ${MAX_WORKSPACE_NAME_LENGTH}자 이하로 입력해 주세요.`)
      return
    }
    if (!normalizedSeasonName) {
      setValidationMessage('시즌 이름을 입력해 주세요.')
      return
    }
    if (normalizedSeasonName.length > MAX_WORKSPACE_NAME_LENGTH) {
      setValidationMessage(`시즌 이름은 ${MAX_WORKSPACE_NAME_LENGTH}자 이하로 입력해 주세요.`)
      return
    }
    if (!memberNames.length) {
      setValidationMessage('함께할 구성원을 한 명 이상 입력해 주세요.')
      return
    }
    if (memberNames.length > MAX_INITIAL_MEMBER_COUNT) {
      setValidationMessage(`구성원은 최대 ${MAX_INITIAL_MEMBER_COUNT}명까지 입력해 주세요.`)
      return
    }
    if (memberNames.some((name) => name.length > MAX_MEMBER_NAME_LENGTH)) {
      setValidationMessage(`구성원 이름은 각각 ${MAX_MEMBER_NAME_LENGTH}자 이하로 입력해 주세요.`)
      return
    }
    if (new Set(memberNames).size !== memberNames.length) {
      setValidationMessage('같은 이름은 구분할 수 있게 다르게 입력해 주세요.')
      return
    }
    if (endDate < startDate) {
      setValidationMessage('종료일은 시작일보다 빠를 수 없습니다.')
      return
    }

    const request: CreateWorkspaceRequest = {
      teamName: normalizedTeamName,
      seasonName: normalizedSeasonName,
      startDate,
      endDate,
      memberNames,
    }
    const pendingToRecover = selectedPendingCreation
      && isPendingWorkspaceCreationRequest(selectedPendingCreation, request)
      ? selectedPendingCreation
      : null

    setCreationAttemptPending(true)
    try {
      const lockResult = await runWithWorkspaceCreationLock(async () => {
        if (pendingToRecover) {
          const recovery = preparePendingWorkspaceRecovery(pendingToRecover)
          if (recovery.status === 'blocked') {
            return { status: 'recoveryBlocked' as const, reason: recovery.reason }
          }
          await attemptCreation({
            request: pendingToRecover.request,
            idempotencyKey: recovery.idempotencyKey,
            creationKey: creationKey.trim() || undefined,
          })
          return { status: 'requested' as const }
        }

        const preparation = prepareWorkspaceCreation(request)
        if (preparation.status === 'blocked') {
          return { status: 'creationBlocked' as const, reason: preparation.reason }
        }
        refreshPendingCreations()
        await attemptCreation({
          request,
          idempotencyKey: preparation.idempotencyKey,
          creationKey: creationKey.trim() || undefined,
        })
        return { status: 'requested' as const }
      })

      if (lockResult.status === 'busy') {
        if (pendingToRecover) {
          setValidationMessage(creationBusyMessage)
        } else {
          setUncertainCreationDraft(request)
          refreshPendingCreations()
        }
        return
      }
      if (lockResult.status === 'unsupported') {
        setValidationMessage(creationLockUnsupportedMessage)
        return
      }
      if (lockResult.value.status === 'creationBlocked') {
        refreshPendingCreations()
        setValidationMessage(lockResult.value.reason === 'pendingLimitReached'
          ? pendingCreationLimitMessage
          : pendingStorageRequiredMessage)
        return
      }
      if (lockResult.value.status === 'recoveryBlocked') {
        refreshPendingCreations()
        if (lockResult.value.reason === 'missing') {
          setValidationMessage('다른 탭에서 이미 확인하거나 정리한 요청입니다. 같은 입력을 새 요청으로 자동 전환하지 않았습니다.')
        } else if (lockResult.value.reason === 'changed') {
          setValidationMessage('다른 탭에서 복구 기록이 변경됐습니다. 같은 입력을 새 요청으로 자동 전환하지 않았습니다.')
        } else {
          setValidationMessage(pendingStorageRequiredMessage)
        }
      }
    } catch (error) {
      setValidationMessage(errorMessage(error))
    } finally {
      setCreationAttemptPending(false)
    }
  }

  const loadPendingCreation = (item: PendingWorkspaceCreationItem) => {
    createMutation.reset()
    setValidationMessage('')
    setSelectedPendingCreation(item)
    setTeamName(item.request.teamName)
    setSeasonName(item.request.seasonName)
    setStartDate(item.request.startDate)
    setEndDate(item.request.endDate)
    setMemberNamesInput(item.request.memberNames.join('\n'))
    requestAnimationFrame(() => teamNameInputRef.current?.focus())
  }

  const startNewCreationRequest = () => {
    createMutation.reset()
    setValidationMessage('')
    setSelectedPendingCreation(null)
    setUncertainCreationDraft(null)
    requestAnimationFrame(() => teamNameInputRef.current?.focus())
  }

  const forgetRecent = (workspace: RecentWorkspace) => {
    forgetRecentWorkspace(workspace.teamId, workspace.seasonId)
    setRecentWorkspaces((current) => current.filter((candidate) =>
      candidate.teamId !== workspace.teamId || candidate.seasonId !== workspace.seasonId,
    ))
  }

  return (
    <main className="onboarding-page">
      <section className="onboarding-story" aria-labelledby="onboarding-title">
        <div className="brand onboarding-brand"><span className="brand-mark" />BATON</div>
        <div>
          <span className="section-kicker">첫 번째 바통</span>
          <h1 id="onboarding-title">사람이 바뀌어도<br />운영은 이어지게.</h1>
          <p>스터디의 역할, 반복 운영, 결정의 이유와 다음 담당자에게 넘길 맥락을 한곳에 남겨보세요.</p>
        </div>
        <ol className="onboarding-points">
          <li><span>01</span><strong>시즌을 열고</strong><small>함께할 기간과 구성원을 정합니다.</small></li>
          <li><span>02</span><strong>역할을 세우고</strong><small>반복되는 책임을 사람과 분리합니다.</small></li>
          <li><span>03</span><strong>바통을 남겨요</strong><small>다음 사람이 바로 움직일 맥락을 모읍니다.</small></li>
        </ol>
      </section>

      <section className="onboarding-form-panel" aria-labelledby="workspace-form-title">
        <div className="onboarding-form-heading">
          <span className="section-kicker">새 작업 공간</span>
          <h2 id="workspace-form-title">우리 스터디를 시작해요</h2>
          <p>지금 입력한 정보로 첫 시즌과 공유 작업 공간을 만듭니다.</p>
        </div>

        <PendingWorkspaceCreationPanel
          items={pendingCreations}
          busy={creationBusy}
          selectedId={selectedPendingMatchesDraft ? selectedPendingCreation.idempotencyKey : null}
          onLoad={loadPendingCreation}
          onRefresh={refreshPendingCreations}
          onFocusForm={() => teamNameInputRef.current?.focus()}
        />

        {creationConfirmationReason && (
          <div className="form-retry-notice pending-recovery-stale" role="status">
            {creationConfirmationReason === 'pendingMissing'
              ? (
                  <p>
                    이 입력의 복구 기록이 다른 탭에서 확인되었거나 폐기되었습니다.
                    작업 공간이 이미 만들어졌을 수 있으니 최근 목록이나 기존 공유 링크를 먼저 확인해 주세요.
                  </p>
                )
              : (
                  <p>
                    이 입력을 제출하려는 동안 다른 탭에서 생성 요청을 처리하고 있었습니다.
                    같은 작업 공간이 이미 만들어졌을 수 있으니 최근 목록이나 다른 탭의 결과를 먼저 확인해 주세요.
                  </p>
                )}
            <button type="button" className="text-button" onClick={startNewCreationRequest}>
              기존 결과를 확인했고 새 요청으로 전환
            </button>
          </div>
        )}

        {recentWorkspaces.length > 0 && (
          <section className="recent-workspaces" aria-labelledby="recent-workspaces-title">
            <div className="recent-workspaces-heading">
              <h3 id="recent-workspaces-title">최근 작업 공간</h3>
              <span>이 브라우저에서 열었던 공간</span>
            </div>
            <ul>
              {recentWorkspaces.map((workspace) => {
                const path = `/teams/${encodeURIComponent(workspace.teamId)}/seasons/${encodeURIComponent(workspace.seasonId)}`
                return (
                  <li key={`${workspace.teamId}:${workspace.seasonId}`}>
                    <Link to={path}>
                      <span><strong>{workspace.teamName}</strong><small>{workspace.seasonName}</small></span>
                      <time dateTime={workspace.lastOpenedAt}>{formatLastOpenedAt(workspace.lastOpenedAt)}</time>
                    </Link>
                    <button
                      type="button"
                      onClick={() => forgetRecent(workspace)}
                      aria-label={`${workspace.teamName} ${workspace.seasonName} 최근 목록에서 지우기`}
                    >
                      지우기
                    </button>
                  </li>
                )
              })}
            </ul>
          </section>
        )}

        <form className="onboarding-form" onSubmit={submit}>
          <label>
            <span>팀 이름</span>
            <input
              required
              autoFocus
              ref={teamNameInputRef}
              maxLength={MAX_WORKSPACE_NAME_LENGTH}
              value={teamName}
              onChange={(event) => setTeamName(event.target.value)}
              placeholder="예: 알고리즘 한 바퀴"
            />
          </label>
          <label>
            <span>시즌 이름</span>
            <input
              required
              maxLength={MAX_WORKSPACE_NAME_LENGTH}
              value={seasonName}
              onChange={(event) => setSeasonName(event.target.value)}
              placeholder="예: 2026 여름 시즌"
            />
          </label>
          <div className="onboarding-date-row">
            <label><span>시작일</span><input required type="date" value={startDate} onChange={(event) => setStartDate(event.target.value)} /></label>
            <label><span>종료일</span><input required type="date" value={endDate} min={startDate || undefined} onChange={(event) => setEndDate(event.target.value)} /></label>
          </div>
          <label>
            <span>구성원 이름</span>
            <textarea
              required
              rows={4}
              value={memberNamesInput}
              onChange={(event) => setMemberNamesInput(event.target.value)}
              placeholder={'박민서\n김준호\n최유진'}
              aria-describedby="member-names-help"
            />
            <small id="member-names-help">줄바꿈 또는 쉼표로 구분해 주세요. 최대 100명, 이름은 각각 100자까지 입력할 수 있어요.</small>
          </label>
          <label>
            <span>파일럿 생성 코드 <small>(선택)</small></span>
            <input
              type="password"
              autoComplete="off"
              value={creationKey}
              onChange={(event) => setCreationKey(event.target.value)}
              placeholder="운영자에게 받은 코드"
            />
            <small>워크스페이스 생성 요청에만 사용하며 이 브라우저에 저장하지 않습니다.</small>
          </label>

          {(validationMessage || createMutation.error) && (
            <p className="form-error" role="alert">{validationMessage || errorMessage(createMutation.error)}</p>
          )}

          <button
            type="submit"
            className="primary-button onboarding-submit"
            disabled={creationBusy || creationConfirmationReason !== null}
          >
            {creationSubmitLabel}
          </button>
        </form>
      </section>
    </main>
  )
}
