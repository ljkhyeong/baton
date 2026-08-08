import type {
  AccountMembership,
  ClaimedAccountMembership,
} from '@/features/membership/types'
import {
  isInstant,
  isJsonObject,
  isUuid,
} from '@/shared/api/responseValidation'

function hasExactKeys(value: Record<string, unknown>, keys: string[]) {
  const actual = Object.keys(value).sort()
  const expected = [...keys].sort()
  return actual.length === expected.length
    && actual.every((key, index) => key === expected[index])
}

export function decodeCurrentAccountMembership(value: unknown): AccountMembership {
  if (!isJsonObject(value) || typeof value.claimed !== 'boolean') {
    throw new Error('계정과 구성원 연결 응답 형식이 올바르지 않습니다.')
  }

  if (!value.claimed) {
    if (!hasExactKeys(value, ['claimed'])) {
      throw new Error('연결되지 않은 계정 응답 형식이 올바르지 않습니다.')
    }
    return { claimed: false }
  }

  if (!hasExactKeys(value, [
    'claimed',
    'accountId',
    'teamId',
    'memberId',
    'claimedAt',
  ])
    || !isUuid(value.accountId)
    || !isUuid(value.teamId)
    || !isUuid(value.memberId)
    || !isInstant(value.claimedAt)) {
    throw new Error('연결된 계정 응답 값이 올바르지 않습니다.')
  }

  return {
    claimed: true,
    accountId: value.accountId,
    teamId: value.teamId,
    memberId: value.memberId,
    claimedAt: value.claimedAt,
  }
}

export function decodeClaimedAccountMembership(
  value: unknown,
): ClaimedAccountMembership {
  if (!isJsonObject(value) || !hasExactKeys(value, [
    'accountId',
    'teamId',
    'memberId',
    'claimedAt',
  ])) {
    throw new Error('계정과 구성원 연결 응답 형식이 올바르지 않습니다.')
  }
  const membership = decodeCurrentAccountMembership({ claimed: true, ...value })
  if (!membership.claimed) {
    throw new Error('계정과 구성원 연결 응답 형식이 올바르지 않습니다.')
  }
  return membership
}

function sameUuid(actual: string, expected: string) {
  return actual.toLowerCase() === expected.toLowerCase()
}

function assertClaimedMembershipScope(
  membership: ClaimedAccountMembership,
  expectedAccountId: string,
  expectedTeamId: string,
  expectedMemberId?: string,
) {
  if (!sameUuid(membership.accountId, expectedAccountId)
    || !sameUuid(membership.teamId, expectedTeamId)
    || (expectedMemberId !== undefined
      && !sameUuid(membership.memberId, expectedMemberId))) {
    throw new Error('계정과 구성원 연결 응답 범위가 요청과 일치하지 않습니다.')
  }
}

export function decodeCurrentAccountMembershipForScope(
  value: unknown,
  expectedAccountId: string,
  expectedTeamId: string,
): AccountMembership {
  const membership = decodeCurrentAccountMembership(value)
  if (membership.claimed) {
    assertClaimedMembershipScope(
      membership,
      expectedAccountId,
      expectedTeamId,
    )
  }
  return membership
}

export function decodeClaimedAccountMembershipForScope(
  value: unknown,
  expectedAccountId: string,
  expectedTeamId: string,
  expectedMemberId: string,
): ClaimedAccountMembership {
  const membership = decodeClaimedAccountMembership(value)
  assertClaimedMembershipScope(
    membership,
    expectedAccountId,
    expectedTeamId,
    expectedMemberId,
  )
  return membership
}
