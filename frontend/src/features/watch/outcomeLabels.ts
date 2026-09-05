import type { ResourceHealth } from './api'

export const outcomeLabels = {
  SUCCESS: '연결 성공',
  HTTP_CLIENT_ERROR: '대상 서버가 요청을 거부했습니다. 주소와 공개 접근 여부를 확인해 주세요.',
  HTTP_SERVER_ERROR: '대상 서버에 오류가 있습니다. 잠시 후 다시 점검해 주세요.',
  DESTINATION_REJECTED: '보안 정책상 점검할 수 없는 주소입니다.',
  DNS_FAILURE: '도메인 주소를 찾지 못했습니다. 주소를 확인해 주세요.',
  CONNECT_TIMEOUT: '대상 서버에 연결하는 시간이 초과됐습니다.',
  READ_TIMEOUT: '대상 서버의 응답을 기다리는 시간이 초과됐습니다.',
  TLS_FAILURE: '보안 연결을 확인하지 못했습니다. 대상 사이트의 인증서를 확인해 주세요.',
  REDIRECT_REJECTED: '이동된 주소가 점검 정책에 맞지 않습니다.',
  TOO_MANY_REDIRECTS: '주소 이동이 너무 많이 반복됐습니다.',
  RESPONSE_TOO_LARGE: '대상 서버의 응답이 점검 허용 크기를 넘었습니다.',
  NETWORK_FAILURE: '대상 서버와 통신하지 못했습니다. 잠시 후 다시 점검해 주세요.',
  INTERNAL_FAILURE: '점검 서비스 오류로 연결 상태를 확인하지 못했습니다.',
} satisfies Record<NonNullable<ResourceHealth['lastOutcome']>, string>
