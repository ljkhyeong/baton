import type {
  CreateWorkspaceResponse,
  RotateAccessKeyResponse,
} from './types'

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isUuid(value: unknown): value is string {
  return typeof value === 'string' && UUID_PATTERN.test(value)
}

function isAccessKey(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0
}

export function decodeCreateWorkspaceResponse(value: unknown): CreateWorkspaceResponse {
  if (!isRecord(value)
    || !isUuid(value.teamId)
    || !isUuid(value.seasonId)
    || !isAccessKey(value.accessKey)) {
    throw new TypeError('Workspace creation response does not contain valid credentials.')
  }

  return {
    teamId: value.teamId,
    seasonId: value.seasonId,
    accessKey: value.accessKey,
  }
}

export function decodeRotateAccessKeyResponse(value: unknown): RotateAccessKeyResponse {
  if (!isRecord(value) || !isAccessKey(value.accessKey)) {
    throw new TypeError('Access key rotation response does not contain a valid access key.')
  }

  return { accessKey: value.accessKey }
}
