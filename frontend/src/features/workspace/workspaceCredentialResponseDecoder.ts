import type {
  CreateWorkspaceResponse,
  RotateAccessKeyResponse,
} from './types'
import { isJsonObject, isNonEmptyString, isUuid } from '@/shared/api/responseValidation'

function isAccessKey(value: unknown): value is string {
  return isNonEmptyString(value)
}

export function decodeCreateWorkspaceResponse(value: unknown): CreateWorkspaceResponse {
  if (!isJsonObject(value)
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
  if (!isJsonObject(value) || !isAccessKey(value.accessKey)) {
    throw new TypeError('Access key rotation response does not contain a valid access key.')
  }

  return { accessKey: value.accessKey }
}
