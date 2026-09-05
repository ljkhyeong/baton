import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useState } from 'react'
import { getResourceHealth, requestResourceCheck } from './api'
import type { ResourceHealthScope } from './api'
import { outcomeLabels } from './outcomeLabels'
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

export function ResourceHealthStatus({ enabled, changesDisabled, targetUrl, title, ...scope }:
  ResourceHealthScope & { enabled: boolean; changesDisabled: boolean; targetUrl: string; title: string }) {
  const queryClient = useQueryClient()
  const [retryAt, setRetryAt] = useState(0)
  const [now, setNow] = useState(Date.now)
  const remainingSeconds = Math.max(0, Math.ceil((retryAt - now) / 1_000))
  function beginCooldown(seconds: number) {
    const startedAt = Date.now()
    setNow(startedAt)
    setRetryAt(startedAt + seconds * 1_000)
  }
  useEffect(() => {
    if (!retryAt) return
    const timer = setInterval(() => {
      const current = Date.now()
      setNow(current)
      if (current >= retryAt) setRetryAt(0)
    }, 1_000)
    return () => clearInterval(timer)
  }, [retryAt])
  const key = useMemo(() => ['teams', scope.teamId, 'seasons', scope.seasonId, 'resource-health', scope.resourceId,
    { accessKey: scope.accessKey, targetUrl }] as const,
  [scope.teamId, scope.seasonId, scope.resourceId, scope.accessKey, targetUrl])
  const query = useQuery({
    queryKey: key,
    queryFn: ({ signal }) => getResourceHealth(scope, signal),
    enabled,
    staleTime: 30_000,
    refetchInterval: 30_000,
    refetchIntervalInBackground: false,
    retry: false,
  })
  useEffect(() => {
    if (!enabled) void queryClient.cancelQueries({ queryKey: key, exact: true })
  }, [enabled, key, queryClient])
  const mutation = useMutation({
    mutationFn: (_previousCheckedAt: string | null) => requestResourceCheck(scope),
    onSuccess: () => {
      beginCooldown(30)
      void queryClient.invalidateQueries({ queryKey: key, exact: true })
    },
    onError: (error) => {
      if (error instanceof ApiError && error.status === 429) beginCooldown(error.retryAfterSeconds ?? 30)
    },
  })
  const result = query.isError ? undefined : query.data
  const hasNewResult = mutation.isSuccess && result?.availability === 'AVAILABLE' && result.lastCheckedAt
    && query.dataUpdatedAt >= mutation.submittedAt
    && (mutation.variables == null || Date.parse(result.lastCheckedAt) > Date.parse(mutation.variables))
  const label = query.isPending ? '연결 상태 확인 중'
      : result ? availabilityLabels[result.availability] || healthLabels[result.health]
        : '연결 상태 확인 불가'

  return (
    <span className="resource-health" role="group" aria-label={`${title} 연결 상태`}>
      <small className={`resource-health-label health-${result ? result.health.toLowerCase() : 'unknown'}`}>
        {label}
      </small>
      {result?.lastCheckedAt && (
        <small>최근 점검 {formatInstant(result.lastCheckedAt)}</small>
      )}
      {result?.availability === 'AVAILABLE' && result.lastOutcome && result.lastOutcome !== 'SUCCESS' && (
        <small>최근 점검: {outcomeLabels[result.lastOutcome]}</small>
      )}
      {result?.availability === 'AVAILABLE' && result.consecutiveFailures != null && result.consecutiveFailures > 0 && (
        <small>연속 연결 실패 {result.consecutiveFailures}회</small>
      )}
      <small>공개 URL 연결 상태이며 로그인 후 접근 권한은 확인하지 않습니다.</small>
      {!changesDisabled && result?.checkRequestAllowed && (
        <button type="button" disabled={mutation.isPending || remainingSeconds > 0}
          onClick={() => mutation.mutate(result.lastCheckedAt)}
          aria-label={`${title} 다시 점검`}>
          {mutation.isPending ? '점검 요청 중' : remainingSeconds > 0 ? `다시 점검 (${remainingSeconds}초)` : '다시 점검'}
        </button>
      )}
      {mutation.isSuccess && <small role="status">{hasNewResult ? '새 점검 결과를 확인했습니다.' : mutation.data.status === 'IN_PROGRESS'
        ? '이미 점검 중입니다. 다음 조회 때 결과를 확인합니다.'
        : '점검을 접수했습니다. 다음 조회 때 결과를 확인합니다.'}</small>}
      {mutation.isError && <small role="alert">{mutation.error.message}</small>}
    </span>
  )
}
