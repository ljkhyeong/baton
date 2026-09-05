import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { isUuid } from '@/shared/api/responseValidation'
import type { AttentionFilter } from './types'

type Selection = {
  open: boolean
  filter: AttentionFilter
  resolutionsOpen: boolean
  editionOpen: boolean
  selectedId: string
  baseId: string
  previousTargetId: string
}

export function useBriefNavigation(scopeKey: string) {
  const location = useLocation()
  const navigate = useNavigate()
  const values = new URLSearchParams(location.search).getAll('brief')
  const linkedId = values.length === 1 && isUuid(values[0]) ? values[0] : ''
  const invalidLink = values.length > 0 && !linkedId
  const identity = JSON.stringify([scopeKey, location.pathname, values])
  const initial: Selection = { open: values.length > 0, filter: { status: 'ACTIVE' }, resolutionsOpen: false,
    editionOpen: values.length > 0, selectedId: linkedId, baseId: '', previousTargetId: '' }
  const [saved, setSaved] = useState({ identity, selection: initial })
  // 계정·작업공간·링크가 바뀐 렌더부터 이전 선택을 사용하지 않는다.
  if (saved.identity !== identity) setSaved({ identity, selection: initial })
  const selection = saved.identity === identity ? saved.selection : initial
  const update = (patch: Partial<Selection>) => setSaved((current) => ({ identity,
    selection: { ...(current.identity === identity ? current.selection : initial), ...patch } }))
  const clearLink = () => {
    const query = new URLSearchParams(location.search)
    query.delete('brief')
    void navigate({ pathname: location.pathname, search: query.toString(), hash: location.hash }, { replace: true })
  }
  return { selection, update, invalidLink, clearLink }
}

export type BriefNavigation = ReturnType<typeof useBriefNavigation>
