import type { operations } from '@/generated/api'

type EditionResponse = operations['getLatestBriefEdition']['responses'][200]['content']['application/json']
type GenerationResponse = operations['generateBriefEdition']['responses'][201]['content']['application/json']

export type BriefEditionItem = Pick<EditionResponse['items'][number],
  'sourceReference' | 'reasonCode' | 'severity' | 'status' | 'observedAt'>

export type BriefEdition = Pick<EditionResponse,
  'editionId' | 'workspaceId' | 'seasonId' | 'generation' | 'weekStart' | 'zoneId' | 'generatedAt'> & {
  items: BriefEditionItem[]
}

export type BriefGeneration = Pick<GenerationResponse, 'editionId' | 'created'>

export type BriefScope = {
  teamId: string
  seasonId: string
  accessKey: string
}
