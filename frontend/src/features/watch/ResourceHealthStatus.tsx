import { onlineManager, skipToken, useIsMutating, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useState, useSyncExternalStore } from 'react'
import { getResourceHealth, requestResourceCheck } from './api'
import type { ResourceHealthScope } from './api'
import { outcomeLabels } from './outcomeLabels'
import { monitoringReasonLabels } from './monitoringReasonLabels'
import { formatInstant } from '@/features/workspace/WorkspaceViews'
import { ApiError } from '@/shared/api/ApiError'
import './resource-health.scss'

const healthLabels = {
  UNKNOWN: '연결 확인 필요', HEALTHY: '연결 정상', DEGRADED: '연결 불안정', BROKEN: '연결 실패',
}
const availabilityLabels = {
  AVAILABLE: '', PENDING: '점검 결과 대기', STALE: '최근 점검 정보 없음',
  UNAVAILABLE: '연결 상태 확인 불가', NOT_MONITORED: '자동 점검 대상 아님',
}

function subscribeOnline(onChange: () => void) {
  return onlineManager.subscribe(onChange)
}

export function ResourceHealthStatus({ enabled, changesDisabled, targetUrl, title, ...scope }:
  ResourceHealthScope & { enabled: boolean; changesDisabled: boolean; targetUrl: string; title: string }) {
  const queryClient = useQueryClient()
  const online = useSyncExternalStore(subscribeOnline, () => onlineManager.isOnline())
  const key = useMemo(() => ['teams', scope.teamId, 'seasons', scope.seasonId, 'resource-health', scope.resourceId,
    { accessKey: scope.accessKey, targetUrl }] as const,
  [scope.teamId, scope.seasonId, scope.resourceId, scope.accessKey, targetUrl])
  const cooldownKey = useMemo(() => [...key, 'check-cooldown'] as const, [key])
  const mutationKey = [...key, 'check-request'] as const
  // 서버가 알려 준 재요청 시각을 자료별로 보존해 역할 이동 후에도 같은 대기시간을 적용한다.
  const { data: retryAt = 0, dataUpdatedAt: cooldownUpdatedAt } = useQuery({ queryKey: cooldownKey, queryFn: skipToken,
    initialData: 0, gcTime: 3_600_000 })
  const checkPending = useIsMutating({ mutationKey, exact: true }) > 0
  const [now, setNow] = useState(Date.now)
  const remainingSeconds = Math.max(0, Math.ceil((retryAt - Math.max(now, cooldownUpdatedAt)) / 1_000))
  function beginCooldown(seconds: number) {
    const startedAt = Date.now()
    setNow(startedAt)
    queryClient.setQueryData<number>(cooldownKey, (previous) => Math.max(previous ?? 0, startedAt + seconds * 1_000))
  }
  useEffect(() => {
    if (!retryAt) return
    const timer = setInterval(() => {
      const current = Date.now()
      setNow(current)
      if (current >= retryAt) queryClient.setQueryData<number>(cooldownKey, (previous) =>
        previous != null && previous > current ? previous : 0)
    }, 1_000)
    return () => clearInterval(timer)
  }, [retryAt, cooldownKey, queryClient])
  const query = useQuery({
    queryKey: key,
    queryFn: ({ signal }) => getResourceHealth(scope, signal),
    enabled,
    staleTime: 30_000,
    refetchInterval: 30_000,
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: 'always',
    refetchOnReconnect: 'always',
    retry: false,
  })
  useEffect(() => {
    if (!enabled) void queryClient.cancelQueries({ queryKey: key, exact: true })
  }, [enabled, key, queryClient])
  const mutation = useMutation({
    mutationKey,
    mutationFn: (_previousConclusiveAt: string | null) => requestResourceCheck(scope),
    onSuccess: () => {
      beginCooldown(30)
      void queryClient.invalidateQueries({ queryKey: key, exact: true })
    },
    onError: (error) => {
      if (error instanceof ApiError && error.status === 429) beginCooldown(error.retryAfterSeconds ?? 30)
    },
  })
  const result = query.isError ? undefined : query.data
  // 서버의 연결 판정을 다시 계산하지 않고, 재조회가 필요한 캐시의 현재 상태 표시만 제한한다.
  const offline = !online || query.isPaused
  const currentResult = !offline && !query.isStale ? result : undefined
  const hasNewResult = mutation.isSuccess && currentResult?.availability === 'AVAILABLE' && currentResult.lastConclusiveAt
    && query.dataUpdatedAt >= mutation.submittedAt
    && (mutation.variables == null || Date.parse(currentResult.lastConclusiveAt) > Date.parse(mutation.variables))
  const label = offline ? '오프라인 · 연결 상태 확인 불가'
      : query.isPending || (query.isFetching && !currentResult) ? '연결 상태 확인 중'
      : currentResult?.availability === 'PENDING' && currentResult.monitoringReason === 'SYNC_PENDING' ? '점검 서비스 동기화 대기'
      : currentResult ? availabilityLabels[currentResult.availability] || healthLabels[currentResult.health]
        : result ? '최근 상태 다시 조회 필요'
        : '연결 상태 확인 불가'

  return (
    <span className="resource-health" role="group" aria-label={`${title} 연결 상태`}>
      <small className={`resource-health-label health-${currentResult ? currentResult.health.toLowerCase() : 'unknown'}`}>
        {label}
      </small>
      {currentResult?.monitoringReason && (currentResult.availability === 'NOT_MONITORED' || currentResult.availability === 'PENDING') && (
        <small>{monitoringReasonLabels[currentResult.monitoringReason]}</small>
      )}
      {result?.lastConclusiveAt && (
        <small>최근 연결 판정 {formatInstant(result.lastConclusiveAt)}</small>
      )}
      {result?.lastCheckedAt && (
        <small>최근 점검 시도 {formatInstant(result.lastCheckedAt)}</small>
      )}
      {currentResult?.availability === 'AVAILABLE' && currentResult.lastOutcome && currentResult.lastOutcome !== 'SUCCESS' && (
        <small>최근 점검: {outcomeLabels[currentResult.lastOutcome]}</small>
      )}
      {currentResult?.availability === 'AVAILABLE' && currentResult.consecutiveFailures != null && currentResult.consecutiveFailures > 0 && (
        <small>연속 연결 실패 {currentResult.consecutiveFailures}회</small>
      )}
      <small>공개 URL 연결 상태이며 로그인 후 접근 권한은 확인하지 않습니다.</small>
      {enabled && (
        <button type="button" disabled={offline || query.isFetching}
          onClick={() => void query.refetch({ cancelRefetch: false })}
          aria-label={`${title} 상태 다시 조회`}>
          {query.isFetching ? '상태 조회 중' : '상태 다시 조회'}
        </button>
      )}
      {!changesDisabled && result?.checkRequestAllowed && (
        <button type="button" disabled={checkPending || remainingSeconds > 0 || !currentResult}
          onClick={() => mutation.mutate(result.lastConclusiveAt)}
          aria-label={`${title} 다시 점검`}>
          {checkPending ? '점검 요청 중' : remainingSeconds > 0 ? `다시 점검 (${remainingSeconds}초)` : '다시 점검'}
        </button>
      )}
      {mutation.isSuccess && <small role="status">{hasNewResult ? '새 점검 결과를 확인했습니다.' : mutation.data.status === 'IN_PROGRESS'
        ? '이미 점검 중입니다. 다음 조회 때 결과를 확인합니다.'
        : '점검을 접수했습니다. 다음 조회 때 결과를 확인합니다.'}</small>}
      {mutation.isError && <small role="alert">{mutation.error.message}</small>}
    </span>
  )
}
