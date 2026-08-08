import type { operations } from '@/generated/api'

type CurrentAccountMembershipOperation =
  operations['getCurrentAccountMembership']
type ClaimAccountMembershipOperation = operations['claimAccountMembership']

export type AccountMembership =
  CurrentAccountMembershipOperation['responses'][200]['content']['application/json']

export type UnclaimedAccountMembership = Extract<
  AccountMembership,
  { claimed: false }
>

export type ClaimedAccountMembership = Extract<
  AccountMembership,
  { claimed: true }
>

export type ClaimAccountMembershipRequest =
  ClaimAccountMembershipOperation['requestBody']['content']['application/json']
