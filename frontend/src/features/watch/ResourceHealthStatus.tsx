import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { getResourceHealth, requestResourceCheck } from './api'
import type { ResourceHealthScope } from './api'
import { formatInstant } from '@/features/workspace/WorkspaceViews'
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
  const key = ['teams', scope.teamId, 'seasons', scope.seasonId, 'resource-health', scope.resourceId,
    { accessKey: scope.accessKey, targetUrl }] as const
  const query = useQuery({
    queryKey: key,
    queryFn: ({ signal }) => getResourceHealth(scope, signal),
    enabled: enabled && !changesDisabled,
    staleTime: 30_000,
    refetchInterval: 30_000,
    refetchIntervalInBackground: false,
    retry: false,
  })
  const mutation = useMutation({
    mutationFn: () => requestResourceCheck(scope),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: key, exact: true }),
  })
  const result = query.isError ? undefined : query.data
  const label = changesDisabled ? '자동 점검 중지'
    : query.isPending ? '연결 상태 확인 중'
      : result ? availabilityLabels[result.availability] || healthLabels[result.health]
        : '연결 상태 확인 불가'

  return (
    <span className="resource-health" role="group" aria-label={`${title} 연결 상태`}>
      <small className={`resource-health-label health-${!changesDisabled && result ? result.health.toLowerCase() : 'unknown'}`}>
        {label}
      </small>
      {!changesDisabled && result?.lastCheckedAt && (
        <small>최근 점검 {formatInstant(result.lastCheckedAt)}</small>
      )}
      <small>공개 URL 연결 상태이며 로그인 후 접근 권한은 확인하지 않습니다.</small>
      {!changesDisabled && result?.checkRequestAllowed && (
        <button type="button" disabled={mutation.isPending} onClick={() => mutation.mutate()}
          aria-label={`${title} 다시 점검`}>
          {mutation.isPending ? '점검 요청 중' : '다시 점검'}
        </button>
      )}
      {mutation.isSuccess && <small role="status">{mutation.data.status === 'IN_PROGRESS'
        ? '이미 점검 중입니다. 다음 조회 때 결과를 확인합니다.'
        : '점검을 접수했습니다. 다음 조회 때 결과를 확인합니다.'}</small>}
      {mutation.isError && <small role="alert">{mutation.error.message}</small>}
    </span>
  )
}
