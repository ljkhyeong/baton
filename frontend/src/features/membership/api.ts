import { getCsrfToken } from '@/features/auth/api'
import {
  decodeClaimedAccountMembershipForScope,
  decodeCurrentAccountMembershipForScope,
} from '@/features/membership/responseDecoder'
import type {
  AccountMembership,
  ClaimAccountMembershipRequest,
  ClaimedAccountMembership,
} from '@/features/membership/types'
import { apiRequest } from '@/shared/api/client'

const CURRENT_MEMBERSHIP_PATH = '/api/v1/account-memberships/current'
const MEMBERSHIP_CLAIM_PATH = '/api/v1/account-membership-claims'
const ACCESS_KEY_HEADER = 'X-Baton-Access-Key'

export function getCurrentAccountMembership(
  accountId: string,
  teamId: string,
  accessKey: string,
): Promise<AccountMembership> {
  return apiRequest(CURRENT_MEMBERSHIP_PATH, {
    method: 'GET',
    headers: { [ACCESS_KEY_HEADER]: accessKey },
    query: { teamId },
    decode: (value) => decodeCurrentAccountMembershipForScope(
      value,
      accountId,
      teamId,
    ),
  })
}

export async function claimAccountMembership(
  request: ClaimAccountMembershipRequest,
  accountId: string,
  accessKey: string,
): Promise<ClaimedAccountMembership> {
  const csrf = await getCsrfToken()
  return apiRequest(MEMBERSHIP_CLAIM_PATH, {
    method: 'POST',
    body: request,
    headers: {
      [ACCESS_KEY_HEADER]: accessKey,
      [csrf.csrfHeaderName]: csrf.csrfToken,
    },
    decode: (value) => decodeClaimedAccountMembershipForScope(
      value,
      accountId,
      request.teamId,
      request.memberId,
    ),
  })
}
