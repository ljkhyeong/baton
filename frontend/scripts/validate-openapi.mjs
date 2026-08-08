import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { load } from 'js-yaml'

const COMMON_RESPONSE_HEADERS = ['X-Request-ID']
const SESSION_MUTATION_HEADERS = ['Origin', 'Sec-Fetch-Site', 'X-CSRF-TOKEN']
const ROUND_ADMIN_MUTATION_HEADERS = ['X-Baton-Access-Key', ...SESSION_MUTATION_HEADERS]
const ROUND_ROOM_ID_SCHEMA = {
  maxLength: 14,
  minLength: 14,
  pattern: '^[abcdefghjkmnpqrstuvwxyz23456789]{4}-[abcdefghjkmnpqrstuvwxyz23456789]{4}-[abcdefghjkmnpqrstuvwxyz23456789]{4}$',
  type: 'string',
}
const EXPECTED_OPERATION_COUNT = 47
const CONTRACT = [
  {
    id: 'getSystemStatus',
    method: 'get',
    path: '/api/v1/system/status',
    statuses: ['200'],
    summary: '시스템 상태 조회',
  },
  {
    id: 'getAuthCsrf',
    method: 'get',
    path: '/api/v1/auth/csrf',
    responseHeaders: ['Cache-Control'],
    responseRequired: ['csrfHeaderName', 'csrfToken'],
    responseSchema: {
      csrfHeaderName: { type: 'string' },
      csrfToken: { type: 'string' },
    },
    statuses: ['200'],
    summary: '인증 CSRF token 준비',
  },
  {
    id: 'getAuthSession',
    method: 'get',
    path: '/api/v1/auth/session',
    responseHeaders: ['Cache-Control'],
    responseVariants: [
      {
        additionalProperties: false,
        required: ['authenticated'],
        schema: {
          authenticated: { enum: [false], type: 'boolean' },
        },
      },
      {
        additionalProperties: false,
        required: ['accountId', 'authenticated', 'csrfHeaderName', 'csrfToken'],
        schema: {
          accountId: { format: 'uuid', type: 'string' },
          authenticated: { enum: [true], type: 'boolean' },
          csrfHeaderName: { type: 'string' },
          csrfToken: { type: 'string' },
        },
      },
    ],
    statuses: ['200'],
    summary: '현재 인증 session 조회',
  },
  {
    id: 'getAuthProviders',
    method: 'get',
    path: '/api/v1/auth/providers',
    responseHeaders: ['Cache-Control'],
    responseRequired: ['providers'],
    responseSchema: {
      providers: { type: 'array' },
      'providers.items': { enum: ['google', 'naver'], type: 'string' },
    },
    statuses: ['200'],
    summary: '로그인 공급자 목록 조회',
  },
  {
    body: true,
    id: 'registerLocalAccount',
    method: 'post',
    path: '/api/v1/auth/local/registrations',
    requestHeaders: SESSION_MUTATION_HEADERS,
    requestRequired: ['displayName', 'email'],
    requestSchema: {
      displayName: { maxLength: 100, minLength: 1, type: 'string' },
      email: { format: 'email', maxLength: 320, minLength: 1, type: 'string' },
    },
    responseHeaders: ['Cache-Control'],
    responseRequired: ['verificationRequired'],
    responseSchema: {
      verificationRequired: { type: 'boolean' },
    },
    statuses: ['202'],
    summary: '자체 이메일 계정 등록',
  },
  {
    body: true,
    id: 'verifyLocalEmail',
    method: 'post',
    path: '/api/v1/auth/local/email-verifications',
    requestHeaders: SESSION_MUTATION_HEADERS,
    requestRequired: ['password', 'token'],
    requestSchema: {
      password: { maxLength: 128, minLength: 12, type: 'string' },
      token: { maxLength: 512, minLength: 32, type: 'string' },
    },
    responseHeaders: ['Cache-Control'],
    statuses: ['204'],
    summary: '자체 이메일 검증과 credential 생성',
  },
  {
    body: true,
    id: 'createLocalAuthSession',
    method: 'post',
    path: '/api/v1/auth/local/session',
    requestContentType: 'application/x-www-form-urlencoded',
    requestHeaders: SESSION_MUTATION_HEADERS,
    requestRequired: ['email', 'password'],
    requestSchema: {
      email: { type: 'string' },
      password: { type: 'string' },
    },
    responseHeaders: ['Cache-Control'],
    statuses: ['204'],
    summary: '자체 이메일 account session 생성',
  },
  {
    id: 'deleteAuthSession',
    method: 'post',
    path: '/api/v1/auth/logout',
    requestHeaders: SESSION_MUTATION_HEADERS,
    responseHeaders: ['Cache-Control', 'Set-Cookie'],
    statuses: ['204'],
    summary: '현재 account session 종료',
  },
  {
    body: true,
    id: 'claimAccountMembership',
    method: 'post',
    path: '/api/v1/account-membership-claims',
    requestHeaders: ROUND_ADMIN_MUTATION_HEADERS,
    requestRequired: ['memberId', 'seasonId', 'teamId'],
    requestSchema: {
      memberId: { format: 'uuid', type: 'string' },
      seasonId: { format: 'uuid', type: 'string' },
      teamId: { format: 'uuid', type: 'string' },
    },
    responseHeaders: ['Cache-Control'],
    responseRequired: ['accountId', 'claimedAt', 'memberId', 'teamId'],
    responseSchema: {
      accountId: { format: 'uuid', type: 'string' },
      claimedAt: { format: 'date-time', type: 'string' },
      memberId: { format: 'uuid', type: 'string' },
      teamId: { format: 'uuid', type: 'string' },
    },
    statuses: ['200'],
    summary: '계정 구성원 membership claim',
  },
  {
    body: true,
    id: 'createRoundRoomMapping',
    method: 'post',
    path: '/api/v1/round-room-mappings',
    requestHeaders: ROUND_ADMIN_MUTATION_HEADERS,
    requestRequired: ['resourceId', 'seasonId', 'teamId'],
    requestSchema: {
      resourceId: { format: 'uuid', type: 'string' },
      seasonId: { format: 'uuid', type: 'string' },
      teamId: { format: 'uuid', type: 'string' },
    },
    responseHeaders: ['Cache-Control'],
    responseRequired: ['createdAt', 'endedAt', 'resourceId', 'roomId', 'seasonId', 'teamId'],
    responseSchema: {
      createdAt: { format: 'date-time', type: 'string' },
      endedAt: { format: 'date-time', nullable: true, type: 'string' },
      resourceId: { format: 'uuid', type: 'string' },
      roomId: ROUND_ROOM_ID_SCHEMA,
      seasonId: { format: 'uuid', type: 'string' },
      teamId: { format: 'uuid', type: 'string' },
    },
    statuses: ['200'],
    summary: 'ROUND room mapping 생성',
  },
  {
    id: 'endRoundRoomMapping',
    method: 'delete',
    path: '/api/v1/round-room-mappings/{roomId}',
    pathParameterSchema: { roomId: ROUND_ROOM_ID_SCHEMA },
    requestHeaders: ROUND_ADMIN_MUTATION_HEADERS,
    responseHeaders: ['Cache-Control'],
    responseRequired: ['createdAt', 'endedAt', 'resourceId', 'roomId', 'seasonId', 'teamId'],
    responseSchema: {
      createdAt: { format: 'date-time', type: 'string' },
      endedAt: { format: 'date-time', type: 'string' },
      resourceId: { format: 'uuid', type: 'string' },
      roomId: ROUND_ROOM_ID_SCHEMA,
      seasonId: { format: 'uuid', type: 'string' },
      teamId: { format: 'uuid', type: 'string' },
    },
    statuses: ['200'],
    summary: 'ROUND room mapping 종료',
  },
  {
    body: true,
    bodyRequired: false,
    id: 'refreshRoundParticipationGrant',
    method: 'post',
    path: '/round/rooms/{roomId}/participation-grant/refresh',
    pathParameterSchema: { roomId: ROUND_ROOM_ID_SCHEMA },
    requestAdditionalProperties: false,
    requestHeaders: SESSION_MUTATION_HEADERS,
    requestRequired: ['resourceId', 'seasonId', 'teamId'],
    requestSchema: {
      resourceId: { format: 'uuid', type: 'string' },
      seasonId: { format: 'uuid', type: 'string' },
      teamId: { format: 'uuid', type: 'string' },
    },
    responseHeaders: ['Cache-Control', 'Set-Cookie'],
    responseRequired: ['expiresAt', 'refreshAfterSeconds'],
    responseSchema: {
      expiresAt: { format: 'int64', type: 'integer' },
      refreshAfterSeconds: {
        format: 'int32',
        maximum: 300,
        minimum: 1,
        type: 'integer',
      },
    },
    statuses: ['200'],
    summary: 'ROUND 참여권 갱신',
  },
  {
    commonResponseHeaders: [],
    id: 'getRoundParticipationJwkSet',
    method: 'get',
    path: '/.well-known/round-participation-jwks.json',
    responseContentType: 'application/jwk-set+json',
    responseHeaders: ['Cache-Control', 'Content-Type'],
    responseRequired: [
      'keys',
      'keys.items.alg',
      'keys.items.e',
      'keys.items.kid',
      'keys.items.kty',
      'keys.items.n',
      'keys.items.use',
    ],
    responseSchema: {
      keys: { type: 'array' },
      'keys.items.alg': { type: 'string' },
      'keys.items.e': { type: 'string' },
      'keys.items.kid': { type: 'string' },
      'keys.items.kty': { type: 'string' },
      'keys.items.n': { type: 'string' },
      'keys.items.use': { type: 'string' },
    },
    statuses: ['200'],
    summary: 'ROUND participation JWK Set 조회',
  },
  {
    body: true,
    id: 'acceptWatchHealthEvent',
    method: 'post',
    path: '/api/v1/internal/resource-health-events',
    requestAdditionalProperties: false,
    requestHeaders: ['Authorization', 'Idempotency-Key'],
    requestHeaderSchema: {
      Authorization: { type: 'string' },
      'Idempotency-Key': { format: 'uuid', type: 'string' },
    },
    requestRequired: [
      'changedAt',
      'currentHealth',
      'eventId',
      'eventType',
      'previousHealth',
      'resourceReference',
      'sourceRevision',
    ],
    requestSchema: {
      attemptId: { format: 'uuid', nullable: true, type: 'string' },
      changedAt: { format: 'date-time', type: 'string' },
      currentHealth: {
        enum: ['UNKNOWN', 'HEALTHY', 'DEGRADED', 'BROKEN'],
        type: 'string',
      },
      eventId: { format: 'uuid', type: 'string' },
      eventType: {
        enum: ['RESOURCE_HEALTH_CHANGED'],
        pattern: '^RESOURCE_HEALTH_CHANGED$',
        type: 'string',
      },
      previousHealth: {
        enum: ['UNKNOWN', 'HEALTHY', 'DEGRADED', 'BROKEN'],
        type: 'string',
      },
      resourceReference: { maxLength: 128, type: 'string' },
      sourceRevision: { format: 'int64', minimum: 0, type: 'integer' },
    },
    responseRequired: ['acceptedAt', 'eventId'],
    responseSchema: {
      acceptedAt: { format: 'date-time', type: 'string' },
      eventId: { format: 'uuid', type: 'string' },
    },
    statuses: ['202', '400', '401', '409'],
    summary: 'WATCH 전용 역할 자료 health 변경 이벤트 수신',
  },
  {
    body: true,
    id: 'createWorkspace',
    method: 'post',
    path: '/api/v1/workspaces',
    requestHeaders: ['Idempotency-Key'],
    requestSchema: {
      memberNames: { maxItems: 100, minItems: 1, type: 'array' },
      'memberNames.items': { maxLength: 100, minLength: 1, type: 'string' },
      seasonName: { maxLength: 100, minLength: 1, type: 'string' },
      teamName: { maxLength: 100, minLength: 1, type: 'string' },
    },
    responseHeaders: ['Cache-Control', 'Location'],
    statuses: ['201', '400', '403', '409', '500'],
    summary: '워크스페이스 생성',
  },
  {
    id: 'getWorkspace',
    method: 'get',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/workspace',
    requestHeaders: ['X-Baton-Access-Key'],
    responseHeaders: ['Cache-Control'],
    responseRequired: [
      'season.roundSchedule',
      'season.roundSchedule.enabled',
      'season.roundSchedule.firstMeetingDate',
      'season.roundSchedule.generationLeadDays',
      'season.roundSchedule.meetingTime',
      'season.roundSchedule.nextOccurrenceDate',
      'season.roundSchedule.recurrence',
      'rounds.items.scheduledOccurrenceDate',
      'handoffItems.items.createdAt',
      'resources.items.createdAt',
      'routines.items.archivedAt',
      'roleHandoffs',
      'roleHandoffs.items.status',
    ],
    responseSchema: {
      'season.roundSchedule': { nullable: true, type: 'object' },
      'season.roundSchedule.enabled': { type: 'boolean' },
      'season.roundSchedule.firstMeetingDate': { format: 'date', type: 'string' },
      'season.roundSchedule.nextOccurrenceDate': { format: 'date', type: 'string' },
      'rounds.items.scheduledOccurrenceDate': { format: 'date', nullable: true, type: 'string' },
      'handoffItems.items.createdAt': { format: 'date-time', nullable: true, type: 'string' },
      'resources.items.createdAt': { format: 'date-time', nullable: true, type: 'string' },
      'routines.items.archivedAt': { format: 'date-time', nullable: true, type: 'string' },
      roleHandoffs: { type: 'array' },
      'roleHandoffs.items.status': {
        enum: ['PREPARING', 'TRANSFERRED', 'ACCEPTED', 'CANCELLED'],
        type: 'string',
      },
    },
    statuses: ['200', '403'],
    summary: '워크스페이스 조회',
  },
  {
    body: true,
    id: 'updateSeason',
    method: 'put',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}',
    requestHeaders: ['X-Baton-Access-Key'],
    requestSchema: {
      name: { maxLength: 100, minLength: 1, type: 'string' },
    },
    statuses: ['200', '400', '403', '404', '409'],
    summary: '시즌 정보 수정',
  },
  {
    body: true,
    id: 'updateRoundSchedule',
    method: 'put',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/round-schedule',
    requestHeaders: ['X-Baton-Access-Key'],
    requestSchema: {
      enabled: { type: 'boolean' },
      generationLeadDays: { maximum: 30, minimum: 0, type: 'integer' },
      timeZone: { maxLength: 64, minLength: 1, type: 'string' },
    },
    responseRequired: [
      'roundSchedule',
      'roundSchedule.enabled',
      'roundSchedule.firstMeetingDate',
      'roundSchedule.generationLeadDays',
      'roundSchedule.meetingTime',
      'roundSchedule.nextOccurrenceDate',
      'roundSchedule.recurrence',
    ],
    responseSchema: {
      roundSchedule: { nullable: true, type: 'object' },
      'roundSchedule.enabled': { type: 'boolean' },
      'roundSchedule.firstMeetingDate': { format: 'date', type: 'string' },
      'roundSchedule.nextOccurrenceDate': { format: 'date', type: 'string' },
    },
    statuses: ['200', '400', '403', '404', '409'],
    summary: '자동 회차 일정 설정',
  },
  {
    body: true,
    id: 'updateSeasonEnding',
    method: 'patch',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/ending',
    requestHeaders: ['X-Baton-Access-Key'],
    requestSchema: {
      ended: { type: 'boolean' },
    },
    statuses: ['200', '400', '403', '404', '409'],
    summary: '시즌 종료 상태 변경',
  },
  {
    body: true,
    id: 'createNextSeason',
    method: 'post',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/successor',
    requestHeaders: ['Idempotency-Key', 'X-Baton-Access-Key'],
    requestSchema: {
      copyRoleIds: { maxItems: 100, minItems: 0, type: 'array' },
      'copyRoleIds.items': { type: 'string' },
      copyRoutineIds: { maxItems: 100, minItems: 0, type: 'array' },
      'copyRoutineIds.items': { type: 'string' },
      name: { maxLength: 100, minLength: 1, type: 'string' },
    },
    responseHeaders: ['Location'],
    statuses: ['201', '400', '403', '404', '409'],
    summary: '다음 시즌 시작',
  },
  {
    body: true,
    id: 'createMember',
    method: 'post',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/members',
    requestHeaders: ['Idempotency-Key', 'X-Baton-Access-Key'],
    requestSchema: {
      name: { maxLength: 100, minLength: 1, type: 'string' },
    },
    statuses: ['201', '400', '403', '404', '409'],
    summary: '구성원 추가',
  },
  {
    body: true,
    id: 'updateMember',
    method: 'put',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/members/{memberId}',
    requestHeaders: ['X-Baton-Access-Key'],
    requestSchema: {
      name: { maxLength: 100, minLength: 1, type: 'string' },
    },
    statuses: ['200', '404', '409'],
    summary: '구성원 이름 수정',
  },
  {
    body: true,
    id: 'updateMemberDeactivation',
    method: 'patch',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/members/{memberId}/deactivation',
    requestHeaders: ['X-Baton-Access-Key'],
    requestSchema: {
      deactivated: { type: 'boolean' },
    },
    statuses: ['200', '404', '409'],
    summary: '구성원 활동 상태 변경',
  },
  {
    id: 'rotateAccessKey',
    method: 'post',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/access-key/rotate',
    requestHeaders: ['Idempotency-Key', 'X-Baton-Access-Key'],
    responseHeaders: ['Cache-Control'],
    statuses: ['200', '409'],
    summary: '접근 키 회전',
  },
  {
    id: 'recoverAccessKey',
    method: 'post',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/access-key/recover',
    requestHeaders: ['Idempotency-Key', 'X-Baton-Recovery-Key'],
    responseHeaders: ['Cache-Control'],
    statuses: ['200', '403'],
    summary: '접근 키 복구',
  },
  {
    body: true,
    id: 'createRole',
    method: 'post',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/roles',
    requestHeaders: ['Idempotency-Key', 'X-Baton-Access-Key'],
    requestSchema: {
      responsibilities: { maxItems: 100, minItems: 0, type: 'array' },
      'responsibilities.items': { maxLength: 500, minLength: 1, type: 'string' },
    },
    statuses: ['201', '409'],
    summary: '역할 생성',
  },
  {
    body: true,
    id: 'updateRole',
    method: 'put',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/roles/{roleId}',
    requestHeaders: ['X-Baton-Access-Key'],
    requestSchema: {
      responsibilities: { maxItems: 100, minItems: 0, type: 'array' },
      'responsibilities.items': { maxLength: 500, minLength: 1, type: 'string' },
    },
    statuses: ['200', '404', '409'],
    summary: '역할 수정',
  },
  {
    body: true,
    id: 'prepareRoleHandoff',
    method: 'post',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/roles/{roleId}/handoffs',
    requestHeaders: ['Idempotency-Key', 'X-Baton-Access-Key'],
    requestSchema: {
      incomingAssignmentEndDate: { format: 'date', nullable: true, type: 'string' },
      incomingAssignmentStartDate: { format: 'date', type: 'string' },
      toMemberId: { format: 'uuid', type: 'string' },
    },
    responseHeaders: ['Location'],
    statuses: ['201', '409'],
    summary: '역할 바통 준비',
  },
  {
    body: true,
    id: 'transferRoleHandoff',
    method: 'patch',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/roles/{roleId}/handoffs/{handoffId}/transfer',
    requestHeaders: ['X-Baton-Access-Key'],
    requestSchema: {
      confirmedByMemberId: { format: 'uuid', type: 'string' },
      warningAcknowledged: { type: 'boolean' },
    },
    statuses: ['200', '404', '409'],
    summary: '역할 바통 전달',
  },
  {
    body: true,
    id: 'acceptRoleHandoff',
    method: 'patch',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/roles/{roleId}/handoffs/{handoffId}/acceptance',
    requestHeaders: ['X-Baton-Access-Key'],
    requestSchema: {
      confirmedByMemberId: { format: 'uuid', type: 'string' },
    },
    responseRequired: ['role.nextMemberId'],
    responseSchema: {
      'role.nextMemberId': { format: 'uuid', nullable: true, type: 'string' },
    },
    statuses: ['200', '404', '409'],
    summary: '역할 바통 수락',
  },
  {
    body: true,
    id: 'cancelRoleHandoff',
    method: 'patch',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/roles/{roleId}/handoffs/{handoffId}/cancellation',
    requestHeaders: ['X-Baton-Access-Key'],
    requestSchema: {
      confirmedByMemberId: { format: 'uuid', type: 'string' },
    },
    responseRequired: ['role.nextMemberId'],
    responseSchema: {
      'role.nextMemberId': { format: 'uuid', nullable: true, type: 'string' },
    },
    statuses: ['200', '404', '409'],
    summary: '역할 바통 취소',
  },
  {
    body: true,
    id: 'createRoleResource',
    method: 'post',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/role-resources',
    requestHeaders: ['Idempotency-Key', 'X-Baton-Access-Key'],
    responseRequired: ['createdAt'],
    responseSchema: {
      createdAt: { format: 'date-time', nullable: true, type: 'string' },
    },
    statuses: ['201', '400'],
    summary: '역할 자료 생성',
  },
  {
    body: true,
    id: 'updateRoleResource',
    method: 'put',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/role-resources/{resourceId}',
    requestHeaders: ['X-Baton-Access-Key'],
    responseRequired: ['createdAt'],
    responseSchema: {
      createdAt: { format: 'date-time', nullable: true, type: 'string' },
    },
    statuses: ['200', '404', '409'],
    summary: '역할 자료 수정',
  },
  {
    body: true,
    id: 'createRoutine',
    method: 'post',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/routines',
    requestHeaders: ['Idempotency-Key', 'X-Baton-Access-Key'],
    responseRequired: ['archivedAt'],
    responseSchema: {
      archivedAt: { format: 'date-time', nullable: true, type: 'string' },
    },
    statuses: ['201', '400', '409'],
    summary: '루틴 생성',
  },
  {
    body: true,
    id: 'updateRoutine',
    method: 'put',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/routines/{routineId}',
    requestHeaders: ['X-Baton-Access-Key'],
    responseRequired: ['archivedAt'],
    responseSchema: {
      archivedAt: { format: 'date-time', nullable: true, type: 'string' },
    },
    statuses: ['200', '404', '409'],
    summary: '루틴 수정',
  },
  {
    body: true,
    id: 'updateRoutineArchive',
    method: 'patch',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/routines/{routineId}/archive',
    requestHeaders: ['X-Baton-Access-Key'],
    requestSchema: {
      archived: { type: 'boolean' },
    },
    responseRequired: ['archivedAt'],
    responseSchema: {
      archivedAt: { format: 'date-time', nullable: true, type: 'string' },
    },
    statuses: ['200', '400', '403', '404', '409'],
    summary: '루틴 보관 상태 변경',
  },
  {
    body: true,
    id: 'createSeasonRound',
    method: 'post',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/rounds',
    requestHeaders: ['Idempotency-Key', 'X-Baton-Access-Key'],
    responseRequired: ['scheduledOccurrenceDate'],
    responseSchema: {
      scheduledOccurrenceDate: { format: 'date', nullable: true, type: 'string' },
    },
    statuses: ['201', '400', '409'],
    summary: '시즌 회차 생성',
  },
  {
    body: true,
    id: 'updateSeasonRound',
    method: 'put',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/rounds/{roundId}',
    requestHeaders: ['X-Baton-Access-Key'],
    statuses: ['200', '400', '403', '404', '409'],
    summary: '시즌 회차 수정',
  },
  {
    body: true,
    id: 'updateSeasonRoundArchive',
    method: 'patch',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/rounds/{roundId}/archive',
    requestHeaders: ['X-Baton-Access-Key'],
    statuses: ['200', '400', '403', '404', '409'],
    summary: '시즌 회차 보관 상태 변경',
  },
  {
    body: true,
    id: 'updateRoutineExecutionCompletion',
    method: 'patch',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/rounds/{roundId}/routine-executions/{executionId}/completion',
    requestHeaders: ['X-Baton-Access-Key'],
    statuses: ['200', '404', '409'],
    summary: '회차 루틴 실행 완료 상태 변경',
  },
  {
    body: true,
    id: 'createDecision',
    method: 'post',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/decisions',
    requestHeaders: ['Idempotency-Key', 'X-Baton-Access-Key'],
    statuses: ['201'],
    summary: '결정 생성',
  },
  {
    body: true,
    id: 'updateDecision',
    method: 'put',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/decisions/{decisionId}',
    requestHeaders: ['X-Baton-Access-Key'],
    statuses: ['200', '404', '409'],
    summary: '결정 수정',
  },
  {
    body: true,
    id: 'updateDecisionArchive',
    method: 'patch',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/decisions/{decisionId}/archive',
    requestHeaders: ['X-Baton-Access-Key'],
    statuses: ['200', '404', '409'],
    summary: '결정 보관 상태 변경',
  },
  {
    body: true,
    id: 'createHandoffItem',
    method: 'post',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/handoff-items',
    requestHeaders: ['Idempotency-Key', 'X-Baton-Access-Key'],
    responseRequired: ['createdAt'],
    responseSchema: {
      createdAt: { format: 'date-time', nullable: true, type: 'string' },
    },
    statuses: ['201'],
    summary: '인수인계 항목 생성',
  },
  {
    body: true,
    id: 'updateHandoffItem',
    method: 'put',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/handoff-items/{itemId}',
    requestHeaders: ['X-Baton-Access-Key'],
    responseRequired: ['createdAt'],
    responseSchema: {
      createdAt: { format: 'date-time', nullable: true, type: 'string' },
    },
    statuses: ['200', '404', '409'],
    summary: '인수인계 항목 수정',
  },
  {
    body: true,
    id: 'updateHandoffItemCompletion',
    method: 'patch',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/handoff-items/{itemId}/completion',
    requestHeaders: ['X-Baton-Access-Key'],
    responseRequired: ['createdAt'],
    responseSchema: {
      createdAt: { format: 'date-time', nullable: true, type: 'string' },
    },
    statuses: ['200', '404', '409'],
    summary: '인수인계 항목 완료 상태 변경',
  },
  {
    body: true,
    id: 'updateHandoffItemArchive',
    method: 'patch',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/handoff-items/{itemId}/archive',
    requestHeaders: ['X-Baton-Access-Key'],
    responseRequired: ['createdAt'],
    responseSchema: {
      createdAt: { format: 'date-time', nullable: true, type: 'string' },
    },
    statuses: ['200', '404', '409'],
    summary: '인수인계 항목 보관 상태 변경',
  },
]
const HTTP_METHODS = ['delete', 'get', 'head', 'options', 'patch', 'post', 'put', 'trace']

const inputPath = resolve(process.argv[2] ?? '')
const document = load(readFileSync(inputPath, 'utf8'))
const failures = []

function sameValues(actual, expected) {
  return [...actual].sort().join('\u0000') === [...expected].sort().join('\u0000')
}

function sameConstraintValue(actual, expected) {
  if (Array.isArray(actual) && Array.isArray(expected)) {
    return sameValues(actual, expected)
  }
  return actual === expected
}

function requiredParameters(operation, location) {
  return (operation.parameters ?? [])
    .filter((parameter) => parameter.in === location && parameter.required)
    .map((parameter) => parameter.name)
}

function resolveSchema(schema) {
  const reference = schema?.$ref
  const prefix = '#/components/schemas/'
  if (!reference?.startsWith(prefix)) return schema
  return document.components?.schemas?.[reference.slice(prefix.length)]
}

function nestedSchema(schema, path) {
  const nested = path.split('.').reduce((current, segment) => {
    const resolved = resolveSchema(current)
    return segment === 'items' ? resolved?.items : resolved?.properties?.[segment]
  }, schema)
  return resolveSchema(nested)
}

function isRequiredPath(schema, path) {
  let current = schema
  for (const segment of path.split('.')) {
    const resolved = resolveSchema(current)
    if (segment === 'items') {
      current = resolved?.items
      continue
    }
    if (!resolved?.required?.includes(segment)) return false
    current = resolved.properties?.[segment]
  }
  return true
}

for (const expected of CONTRACT) {
  const operation = document.paths?.[expected.path]?.[expected.method]
  if (!operation) {
    failures.push(`${expected.method.toUpperCase()} ${expected.path} is missing`)
    continue
  }

  if (operation.operationId !== expected.id) {
    failures.push(`${expected.path} operationId: ${operation.operationId} != ${expected.id}`)
  }
  if (operation.summary !== expected.summary) {
    failures.push(`${expected.id} summary: ${operation.summary} != ${expected.summary}`)
  }
  if (Boolean(operation.requestBody) !== Boolean(expected.body)) {
    failures.push(`${expected.id} requestBody presence is incorrect`)
  }
  const expectedBodyRequired = expected.body ? (expected.bodyRequired ?? true) : undefined
  if (expected.body && operation.requestBody?.required !== expectedBodyRequired) {
    failures.push(`${expected.id} requestBody required flag is incorrect`)
  }
  const requestContentType = expected.requestContentType ?? 'application/json'
  const requestSchema = resolveSchema(
    operation.requestBody?.content?.[requestContentType]?.schema,
  )
  if (expected.requestAdditionalProperties !== undefined
    && requestSchema?.additionalProperties !== expected.requestAdditionalProperties) {
    failures.push(
      `${expected.id} request schema additionalProperties: `
      + `${requestSchema?.additionalProperties} != ${expected.requestAdditionalProperties}`,
    )
  }
  for (const [propertyPath, expectedConstraints] of Object.entries(expected.requestSchema ?? {})) {
    const propertySchema = nestedSchema(requestSchema, propertyPath)
    if (!propertySchema) {
      failures.push(`${expected.id} request schema ${propertyPath} is missing`)
      continue
    }
    for (const [constraint, expectedValue] of Object.entries(expectedConstraints)) {
      if (!sameConstraintValue(propertySchema[constraint], expectedValue)) {
        failures.push(
          `${expected.id} request schema ${propertyPath}.${constraint}: `
          + `${propertySchema[constraint]} != ${expectedValue}`,
        )
      }
    }
  }
  const responseContentType = expected.responseContentType ?? 'application/json'
  const successResponseSchema = resolveSchema(
    operation.responses?.[expected.statuses[0]]?.content?.[responseContentType]?.schema,
  )
  const actualResponseVariants = successResponseSchema?.oneOf ?? []
  if (expected.responseVariants) {
    if (actualResponseVariants.length !== expected.responseVariants.length) {
      failures.push(`${expected.id} response oneOf variants are incorrect`)
    }
    expected.responseVariants.forEach((expectedVariant, index) => {
      const actualVariant = resolveSchema(actualResponseVariants[index])
      if (!actualVariant) {
        failures.push(`${expected.id} response variant ${index} is missing`)
        return
      }
      if (actualVariant.additionalProperties !== expectedVariant.additionalProperties) {
        failures.push(`${expected.id} response variant ${index} additionalProperties is incorrect`)
      }
      if (!sameValues(actualVariant.required ?? [], expectedVariant.required)) {
        failures.push(`${expected.id} response variant ${index} required fields are incorrect`)
      }
      if (!sameValues(
        Object.keys(actualVariant.properties ?? {}),
        Object.keys(expectedVariant.schema),
      )) {
        failures.push(`${expected.id} response variant ${index} properties are incorrect`)
      }
      for (const [propertyName, expectedConstraints] of Object.entries(
        expectedVariant.schema,
      )) {
        const propertySchema = resolveSchema(actualVariant.properties?.[propertyName])
        if (!propertySchema) {
          failures.push(`${expected.id} response variant ${index} ${propertyName} is missing`)
          continue
        }
        for (const [constraint, expectedValue] of Object.entries(expectedConstraints)) {
          if (!sameConstraintValue(propertySchema[constraint], expectedValue)) {
            failures.push(
              `${expected.id} response variant ${index} ${propertyName}.${constraint}: `
              + `${propertySchema[constraint]} != ${expectedValue}`,
            )
          }
        }
      }
    })
  }
  for (const [propertyPath, expectedConstraints] of Object.entries(expected.responseSchema ?? {})) {
    const propertySchema = nestedSchema(successResponseSchema, propertyPath)
    if (!propertySchema) {
      failures.push(`${expected.id} response schema ${propertyPath} is missing`)
      continue
    }
    for (const [constraint, expectedValue] of Object.entries(expectedConstraints)) {
      if (!sameConstraintValue(propertySchema[constraint], expectedValue)) {
        failures.push(
          `${expected.id} response schema ${propertyPath}.${constraint}: `
          + `${propertySchema[constraint]} != ${expectedValue}`,
        )
      }
    }
  }
  for (const propertyPath of expected.responseRequired ?? []) {
    if (!isRequiredPath(successResponseSchema, propertyPath)) {
      failures.push(`${expected.id} response schema ${propertyPath} must be required`)
    }
  }
  for (const propertyPath of expected.requestRequired ?? []) {
    if (!isRequiredPath(requestSchema, propertyPath)) {
      failures.push(`${expected.id} request schema ${propertyPath} must be required`)
    }
  }

  const expectedPathParameters = [...expected.path.matchAll(/\{([^}]+)}/g)].map((match) => match[1])
  const actualPathParameters = requiredParameters(operation, 'path')
  if (!sameValues(actualPathParameters, expectedPathParameters)) {
    failures.push(`${expected.id} path parameters are incorrect`)
  }
  for (const [parameterName, expectedConstraints] of Object.entries(
    expected.pathParameterSchema ?? {},
  )) {
    const parameter = (operation.parameters ?? []).find(
      (candidate) => candidate.in === 'path' && candidate.name === parameterName,
    )
    if (!parameter) {
      failures.push(`${expected.id} path parameter ${parameterName} is missing`)
      continue
    }
    for (const [constraint, expectedValue] of Object.entries(expectedConstraints)) {
      if (!sameConstraintValue(parameter.schema?.[constraint], expectedValue)) {
        failures.push(
          `${expected.id} path parameter ${parameterName}.${constraint}: `
          + `${parameter.schema?.[constraint]} != ${expectedValue}`,
        )
      }
    }
  }

  const actualRequestHeaders = requiredParameters(operation, 'header')
  if (!sameValues(actualRequestHeaders, expected.requestHeaders ?? [])) {
    failures.push(`${expected.id} required request headers are incorrect`)
  }
  for (const [headerName, expectedConstraints] of Object.entries(
    expected.requestHeaderSchema ?? {},
  )) {
    const header = (operation.parameters ?? []).find(
      (parameter) => parameter.in === 'header' && parameter.name === headerName,
    )
    if (!header) {
      failures.push(`${expected.id} request header ${headerName} is missing`)
      continue
    }
    for (const [constraint, expectedValue] of Object.entries(expectedConstraints)) {
      if (!sameConstraintValue(header.schema?.[constraint], expectedValue)) {
        failures.push(
          `${expected.id} request header ${headerName}.${constraint}: `
          + `${header.schema?.[constraint]} != ${expectedValue}`,
        )
      }
    }
  }

  const actualStatuses = Object.keys(operation.responses ?? {})
  if (!sameValues(actualStatuses, expected.statuses)) {
    failures.push(`${expected.id} response statuses are incorrect`)
  }

  for (const [status, response] of Object.entries(operation.responses ?? {})) {
    const commonResponseHeaders = expected.commonResponseHeaders ?? COMMON_RESPONSE_HEADERS
    const expectedResponseHeaders = status === expected.statuses[0]
      ? [...commonResponseHeaders, ...(expected.responseHeaders ?? [])]
      : commonResponseHeaders
    const actualResponseHeaders = Object.keys(response?.headers ?? {})
    if (!sameValues(actualResponseHeaders, expectedResponseHeaders)) {
      failures.push(`${expected.id} ${status} response headers are incorrect`)
    }
  }
}

const operationCount = Object.values(document.paths ?? {}).reduce(
  (count, pathItem) => count + HTTP_METHODS.filter((method) => pathItem?.[method]).length,
  0,
)

if (CONTRACT.length !== EXPECTED_OPERATION_COUNT) {
  failures.push(`contract baseline count: ${CONTRACT.length} != ${EXPECTED_OPERATION_COUNT}`)
}
if (operationCount !== EXPECTED_OPERATION_COUNT) {
  failures.push(`operation count: ${operationCount} != ${EXPECTED_OPERATION_COUNT}`)
}
if (document.servers?.[0]?.url !== '/') failures.push('OpenAPI server must be same-origin /')
if (!document.components?.schemas?.ErrorResponse) failures.push('ErrorResponse component is missing')

if (failures.length > 0) {
  failures.forEach((failure) => console.error(`- ${failure}`))
  process.exit(1)
}

console.log(`Validated ${EXPECTED_OPERATION_COUNT} OpenAPI operations`)
