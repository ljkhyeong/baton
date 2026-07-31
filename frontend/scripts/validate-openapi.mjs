import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { load } from 'js-yaml'

const COMMON_RESPONSE_HEADERS = ['X-Request-ID']
const CONTRACT = [
  {
    id: 'getRoundJwkSet',
    method: 'get',
    path: '/.well-known/jwks.json',
    responseHeadersByStatus: {
      200: ['Cache-Control', 'ETag'],
      304: ['Cache-Control', 'ETag'],
    },
    security: [],
    statuses: ['200', '304'],
    summary: 'ROUND 참여권 공개키 조회',
  },
  {
    id: 'getSystemStatus',
    method: 'get',
    path: '/api/v1/system/status',
    statuses: ['200'],
    summary: '시스템 상태 조회',
  },
  {
    id: 'authorizeGoogleOidc',
    method: 'get',
    path: '/api/v1/auth/oidc/authorization/google',
    responseHeadersByStatus: {
      302: ['Cache-Control', 'Location'],
    },
    security: [],
    statuses: ['302'],
    summary: 'Google OIDC 로그인 시작',
  },
  {
    id: 'handleGoogleOidcCallback',
    method: 'get',
    path: '/api/v1/auth/oidc/callback/google',
    responseHeadersByStatus: {
      302: ['Cache-Control', 'Location'],
      401: ['Cache-Control'],
    },
    security: [],
    statuses: ['302', '401'],
    summary: 'Google OIDC callback 처리',
  },
  {
    id: 'getIdentitySession',
    method: 'get',
    path: '/api/v1/auth/session',
    responseHeadersForAllStatuses: ['Cache-Control'],
    responseRequired: [
      'authenticated',
      'accountId',
      'csrfHeaderName',
      'csrfToken',
      'oidcEnabled',
    ],
    responseSchema: {
      accountId: { format: 'uuid', nullable: true, type: 'string' },
      authenticated: { type: 'boolean' },
      csrfHeaderName: { nullable: true, type: 'string' },
      csrfToken: { nullable: true, type: 'string' },
      oidcEnabled: { type: 'boolean' },
    },
    security: [{}, { batonSession: [] }],
    statuses: ['200'],
    summary: '로그인 세션 조회',
  },
  {
    id: 'getMe',
    method: 'get',
    path: '/api/v1/me',
    responseHeadersForAllStatuses: ['Cache-Control'],
    responseSchema: {
      accountId: { format: 'uuid', type: 'string' },
    },
    security: [{ batonSession: [] }],
    statuses: ['200', '401'],
    summary: '내 계정 조회',
  },
  {
    id: 'logoutSession',
    method: 'post',
    path: '/api/v1/session/logout',
    requestHeaders: ['X-CSRF-TOKEN'],
    responseHeadersForAllStatuses: ['Cache-Control'],
    security: [{ batonSession: [] }],
    statuses: ['204', '403'],
    summary: '로그인 세션 종료',
  },
  {
    body: true,
    id: 'issueBootstrapInvitation',
    method: 'post',
    path: '/api/v1/identity/bootstrap-invitations',
    requestHeaders: ['Idempotency-Key', 'X-Baton-Identity-Bootstrap-Key'],
    requestHeaderSchema: {
      'Idempotency-Key': {
        format: 'uuid',
        maxLength: 36,
        minLength: 36,
        pattern: '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
      },
    },
    requestSchema: {
      memberId: { format: 'uuid', type: 'string' },
      teamId: { format: 'uuid', type: 'string' },
    },
    responseHeadersForAllStatuses: ['Cache-Control'],
    responseRequired: ['token'],
    responseSchema: {
      expiresAt: { format: 'date-time', type: 'string' },
      invitationId: { format: 'uuid', type: 'string' },
      issuedAt: { format: 'date-time', type: 'string' },
      memberId: { format: 'uuid', type: 'string' },
      teamId: { format: 'uuid', type: 'string' },
      token: { type: 'string' },
    },
    security: [{ identityBootstrapKey: [] }],
    statuses: ['201', '200', '400', '403', '404', '409', '503'],
    summary: '기존 팀 OWNER bootstrap 초대 발급',
  },
  {
    body: true,
    id: 'previewInvitation',
    method: 'post',
    path: '/api/v1/identity/invitations/preview',
    requestHeaders: ['X-CSRF-TOKEN'],
    requestSchema: {
      token: { maxLength: 200, minLength: 1, type: 'string' },
    },
    responseHeadersForAllStatuses: ['Cache-Control'],
    responseSchema: {
      alreadyAccepted: { type: 'boolean' },
      expiresAt: { format: 'date-time', type: 'string' },
      memberId: { format: 'uuid', type: 'string' },
      memberName: { type: 'string' },
      role: { enum: ['MEMBER', 'OWNER'], type: 'string' },
      teamId: { format: 'uuid', type: 'string' },
      teamName: { type: 'string' },
    },
    security: [{ batonSession: [] }],
    statuses: ['200', '400', '401', '403', '404', '409', '410'],
    summary: '구성원 초대 미리보기',
  },
  {
    body: true,
    id: 'acceptInvitation',
    method: 'post',
    path: '/api/v1/identity/invitations/accept',
    requestHeaders: ['X-CSRF-TOKEN'],
    requestSchema: {
      token: { maxLength: 200, minLength: 1, type: 'string' },
    },
    responseHeadersForAllStatuses: ['Cache-Control'],
    responseSchema: {
      accountId: { format: 'uuid', type: 'string' },
      boundAt: { format: 'date-time', type: 'string' },
      invitationId: { format: 'uuid', type: 'string' },
      memberId: { format: 'uuid', type: 'string' },
      role: { enum: ['MEMBER', 'OWNER'], type: 'string' },
      teamId: { format: 'uuid', type: 'string' },
    },
    security: [{ batonSession: [] }],
    statuses: ['200', '400', '401', '403', '404', '409', '410'],
    summary: '구성원 초대 수락',
  },
  {
    id: 'getTeamMembership',
    method: 'get',
    path: '/api/v1/teams/{teamId}/membership',
    responseHeadersForAllStatuses: ['Cache-Control'],
    responseSchema: {
      accountId: { format: 'uuid', type: 'string' },
      boundAt: { format: 'date-time', type: 'string' },
      memberId: { format: 'uuid', type: 'string' },
      role: { enum: ['MEMBER', 'OWNER'], type: 'string' },
      teamId: { format: 'uuid', type: 'string' },
    },
    security: [{ batonSession: [] }],
    statuses: ['200', '401', '404'],
    summary: '팀 로그인 소속 조회',
  },
  {
    body: true,
    id: 'issueMemberInvitation',
    method: 'post',
    path: '/api/v1/teams/{teamId}/member-invitations',
    requestHeaders: ['Idempotency-Key', 'X-CSRF-TOKEN'],
    requestHeaderSchema: {
      'Idempotency-Key': {
        format: 'uuid',
        maxLength: 36,
        minLength: 36,
        pattern: '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
      },
    },
    requestSchema: {
      memberId: { format: 'uuid', type: 'string' },
    },
    responseHeadersForAllStatuses: ['Cache-Control'],
    responseRequired: ['token'],
    responseSchema: {
      expiresAt: { format: 'date-time', type: 'string' },
      invitationId: { format: 'uuid', type: 'string' },
      issuedAt: { format: 'date-time', type: 'string' },
      memberId: { format: 'uuid', type: 'string' },
      teamId: { format: 'uuid', type: 'string' },
      token: { type: 'string' },
    },
    security: [{ batonSession: [] }],
    statuses: ['201', '200', '400', '401', '403', '404', '409', '503'],
    summary: '일반 구성원 초대 발급',
  },
  {
    id: 'listMemberInvitations',
    method: 'get',
    path: '/api/v1/teams/{teamId}/member-invitations',
    responseHeadersForAllStatuses: ['Cache-Control'],
    responseRequired: [
      'items.invitationId',
      'items.teamId',
      'items.memberId',
      'items.issuedAt',
      'items.expiresAt',
    ],
    responseSchema: {
      'items.expiresAt': { format: 'date-time', type: 'string' },
      'items.invitationId': { format: 'uuid', type: 'string' },
      'items.issuedAt': { format: 'date-time', type: 'string' },
      'items.memberId': { format: 'uuid', type: 'string' },
      'items.teamId': { format: 'uuid', type: 'string' },
    },
    security: [{ batonSession: [] }],
    statuses: ['200', '401', '403'],
    summary: '열린 구성원 초대 목록',
  },
  {
    id: 'revokeMemberInvitation',
    method: 'post',
    path: '/api/v1/teams/{teamId}/member-invitations/{invitationId}/revocation',
    requestHeaders: ['X-CSRF-TOKEN'],
    responseHeadersForAllStatuses: ['Cache-Control'],
    responseSchema: {
      invitationId: { format: 'uuid', type: 'string' },
      revokedAt: { format: 'date-time', type: 'string' },
    },
    security: [{ batonSession: [] }],
    statuses: ['200', '400', '401', '403', '404', '409', '410'],
    summary: '구성원 초대 폐기',
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
    id: 'openRoleResourceLink',
    method: 'post',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/role-resources/{resourceId}/open-link',
    requestHeaders: ['Idempotency-Key', 'X-Baton-Access-Key'],
    requestHeaderSchema: {
      'Idempotency-Key': {
        format: 'uuid',
        maxLength: 36,
        minLength: 36,
        pattern: '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
      },
    },
    requestSchema: {
      expiresAt: { format: 'date-time', type: 'string' },
    },
    responseHeaders: ['Cache-Control'],
    statuses: ['200', '400', '403', '404', '409', '502'],
    summary: '역할 자료 열기 링크 해석',
  },
  {
    id: 'issueRoundParticipationGrant',
    method: 'post',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/role-resources/{resourceId}/round-participation-grant',
    requestHeaders: ['Origin', 'Sec-Fetch-Site', 'X-CSRF-TOKEN'],
    responseHeadersByStatus: {
      204: ['Cache-Control', 'Set-Cookie'],
      403: ['Cache-Control'],
      404: ['Cache-Control'],
      409: ['Cache-Control'],
      503: ['Cache-Control'],
    },
    security: [{ batonSession: [] }],
    statuses: ['204', '403', '404', '409', '503'],
    summary: 'ROUND participant 참여권 발급',
  },
  {
    id: 'issueRoundRoomParticipationGrant',
    method: 'post',
    path: '/api/v1/round/rooms/{roomId}/participation-grant',
    pathParameterSchema: {
      roomId: {
        maxLength: 14,
        minLength: 14,
        pattern:
          '^[abcdefghjkmnpqrstuvwxyz23456789]{4}'
          + '(?:-[abcdefghjkmnpqrstuvwxyz23456789]{4}){2}$',
        type: 'string',
      },
    },
    requestHeaders: ['Origin', 'Sec-Fetch-Site', 'X-CSRF-TOKEN'],
    responseHeadersByStatus: {
      204: ['Cache-Control', 'Set-Cookie'],
      403: ['Cache-Control'],
      409: ['Cache-Control'],
      503: ['Cache-Control'],
    },
    security: [{ batonSession: [] }],
    statuses: ['204', '403', '409', '503'],
    summary: '복사한 ROUND room 참여권 발급',
  },
  {
    body: true,
    id: 'createRoutine',
    method: 'post',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/routines',
    requestHeaders: ['Idempotency-Key', 'X-Baton-Access-Key'],
    statuses: ['201', '400', '409'],
    summary: '루틴 생성',
  },
  {
    body: true,
    id: 'updateRoutine',
    method: 'put',
    path: '/api/v1/teams/{teamId}/seasons/{seasonId}/routines/{routineId}',
    requestHeaders: ['X-Baton-Access-Key'],
    statuses: ['200', '404', '409'],
    summary: '루틴 수정',
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
  if (
    Object.hasOwn(expected, 'security')
    && JSON.stringify(operation.security ?? null) !== JSON.stringify(expected.security)
  ) {
    failures.push(`${expected.id} security requirements are incorrect`)
  }
  if (expected.body && operation.requestBody?.required !== true) {
    failures.push(`${expected.id} requestBody must be required`)
  }
  const requestSchema = resolveSchema(operation.requestBody?.content?.['application/json']?.schema)
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
  const successResponseSchema = resolveSchema(
    operation.responses?.[expected.statuses[0]]?.content?.['application/json']?.schema,
  )
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

  const expectedPathParameters = [...expected.path.matchAll(/\{([^}]+)}/g)].map((match) => match[1])
  const actualPathParameters = requiredParameters(operation, 'path')
  if (!sameValues(actualPathParameters, expectedPathParameters)) {
    failures.push(`${expected.id} path parameters are incorrect`)
  }
  for (const [parameterName, expectedConstraints] of Object.entries(
    expected.pathParameterSchema ?? {},
  )) {
    const parameterSchema = (operation.parameters ?? [])
      .find((parameter) => parameter.in === 'path' && parameter.name === parameterName)
      ?.schema
    if (!parameterSchema) {
      failures.push(`${expected.id} path parameter schema ${parameterName} is missing`)
      continue
    }
    for (const [constraint, expectedValue] of Object.entries(expectedConstraints)) {
      if (!sameConstraintValue(parameterSchema[constraint], expectedValue)) {
        failures.push(
          `${expected.id} path parameter schema ${parameterName}.${constraint}: `
          + `${parameterSchema[constraint]} != ${expectedValue}`,
        )
      }
    }
  }

  const dualWorkspaceAuthorization =
    expected.path.startsWith('/api/v1/teams/{teamId}/seasons/{seasonId}')
    && ![
      'issueRoundParticipationGrant',
      'recoverAccessKey',
      'rotateAccessKey',
    ].includes(expected.id)
  const expectedRequiredHeaders = (expected.requestHeaders ?? []).filter((header) =>
    !(dualWorkspaceAuthorization && header === 'X-Baton-Access-Key'))
  const actualRequestHeaders = requiredParameters(operation, 'header')
  if (!sameValues(actualRequestHeaders, expectedRequiredHeaders)) {
    failures.push(`${expected.id} required request headers are incorrect`)
  }
  for (const [headerName, expectedConstraints] of Object.entries(
    expected.requestHeaderSchema ?? {},
  )) {
    const headerSchema = (operation.parameters ?? [])
      .find((parameter) => parameter.in === 'header' && parameter.name === headerName)
      ?.schema
    if (!headerSchema) {
      failures.push(`${expected.id} request header schema ${headerName} is missing`)
      continue
    }
    for (const [constraint, expectedValue] of Object.entries(expectedConstraints)) {
      if (headerSchema[constraint] !== expectedValue) {
        failures.push(
          `${expected.id} request header schema ${headerName}.${constraint}: `
          + `${headerSchema[constraint]} != ${expectedValue}`,
        )
      }
    }
  }

  const actualStatuses = Object.keys(operation.responses ?? {})
  if (!sameValues(actualStatuses, expected.statuses)) {
    failures.push(`${expected.id} response statuses are incorrect`)
  }

  for (const [status, response] of Object.entries(operation.responses ?? {})) {
    const operationSpecificHeaders = expected.responseHeadersByStatus?.[status]
      ?? expected.responseHeadersForAllStatuses
      ?? (status === expected.statuses[0] ? expected.responseHeaders ?? [] : [])
    const expectedResponseHeaders = [...COMMON_RESPONSE_HEADERS, ...operationSpecificHeaders]
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

if (operationCount !== CONTRACT.length) failures.push(`operation count: ${operationCount} != ${CONTRACT.length}`)
if (document.servers?.[0]?.url !== '/') failures.push('OpenAPI server must be same-origin /')
if (!document.components?.schemas?.ErrorResponse) failures.push('ErrorResponse component is missing')
const sessionScheme = document.components?.securitySchemes?.batonSession
if (
  sessionScheme?.type !== 'apiKey'
  || sessionScheme?.in !== 'cookie'
  || sessionScheme?.name !== '__Host-baton_session'
  || !sessionScheme?.description?.includes('Path=/')
  || !sessionScheme?.description?.includes('ROUND edge는 이 cookie를')
) {
  failures.push('batonSession cookie security scheme is incorrect')
}
const bootstrapScheme = document.components?.securitySchemes?.identityBootstrapKey
if (
  bootstrapScheme?.type !== 'apiKey'
  || bootstrapScheme?.in !== 'header'
  || bootstrapScheme?.name !== 'X-Baton-Identity-Bootstrap-Key'
) {
  failures.push('identityBootstrapKey security scheme is incorrect')
}
const legacyWorkspaceScheme =
  document.components?.securitySchemes?.legacyWorkspaceAccessKey
if (
  legacyWorkspaceScheme?.type !== 'apiKey'
  || legacyWorkspaceScheme?.in !== 'header'
  || legacyWorkspaceScheme?.name !== 'X-Baton-Access-Key'
) {
  failures.push('legacyWorkspaceAccessKey security scheme is incorrect')
}

for (const [path, pathItem] of Object.entries(document.paths ?? {})) {
  if (!path.startsWith('/api/v1/teams/{teamId}/seasons/{seasonId}')) continue

  for (const method of HTTP_METHODS) {
    const operation = pathItem?.[method]
    if (!operation) continue
    if (operation.operationId === 'issueRoundParticipationGrant') continue

    if (operation.operationId === 'recoverAccessKey') continue
    if (operation.operationId === 'rotateAccessKey') {
      if (
        JSON.stringify(operation.security ?? null)
        !== JSON.stringify([{ legacyWorkspaceAccessKey: [] }])
      ) {
        failures.push('rotateAccessKey must use only legacyWorkspaceAccessKey')
      }
      continue
    }

    if (
      JSON.stringify(operation.security ?? null)
      !== JSON.stringify([
        { batonSession: [] },
        { legacyWorkspaceAccessKey: [] },
      ])
    ) {
      failures.push(`${operation.operationId} workspace security requirements are incorrect`)
    }

    const accessKeyHeader = (operation.parameters ?? []).find((parameter) =>
      parameter.in === 'header' && parameter.name === 'X-Baton-Access-Key')
    if (accessKeyHeader?.required === true) {
      failures.push(`${operation.operationId} legacy access key must be optional for session auth`)
    }

    if (['post', 'put', 'patch', 'delete'].includes(method)) {
      const csrfHeader = (operation.parameters ?? []).find((parameter) =>
        parameter.in === 'header' && parameter.name === 'X-CSRF-TOKEN')
      if (!csrfHeader || csrfHeader.required === true) {
        failures.push(
          `${operation.operationId} must describe conditional session CSRF header`,
        )
      }
    }
  }
}

if (failures.length > 0) {
  failures.forEach((failure) => console.error(`- ${failure}`))
  process.exit(1)
}

console.log(`Validated ${CONTRACT.length} OpenAPI operations`)
