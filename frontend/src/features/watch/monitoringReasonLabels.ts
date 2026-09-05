import type { ResourceHealth } from './api'

export const monitoringReasonLabels = {
  INTEGRATION_DISABLED: '연결 상태 점검 서비스가 연동되어 있지 않습니다.',
  MONITORING_PAUSED: '자동 점검을 일시 중지한 상태입니다.',
  SEASON_ENDED: '종료된 시즌의 자료는 자동으로 점검하지 않습니다.',
  RESOURCE_ARCHIVED: '보관한 자료는 자동으로 점검하지 않습니다.',
  URL_NOT_ELIGIBLE: '자동 점검에 사용할 수 없는 URL 형식입니다. 자료 주소를 확인해 주세요.',
  MONITOR_INACTIVE: '이 자료의 자동 점검이 비활성화되어 있습니다.',
  SYNC_PENDING: '자료 주소를 점검 서비스에 반영하고 있습니다. 잠시 후 다시 확인해 주세요.',
} satisfies Record<NonNullable<ResourceHealth['monitoringReason']>, string>
