import { useEffect, useRef, useState } from 'react'

export type WorkspaceConflictRecoveryStatus = 'refreshing' | 'failed'

type RecoveryState = {
  generation: number
  status: WorkspaceConflictRecoveryStatus
  successMessage: string
}

type RefetchResult = {
  isSuccess: boolean
}

type UseWorkspaceConflictRecoveryOptions = {
  scopeKey: string
  refetchWorkspace: () => Promise<RefetchResult>
  discardEditors: () => void
  notify: (message: string, tone: 'success' | 'error') => void
}

const refreshFailureMessage =
  '최신 기록을 불러오지 못했어요. 연결을 확인한 뒤 다시 시도해 주세요.'

export function useWorkspaceConflictRecovery({
  scopeKey,
  refetchWorkspace,
  discardEditors,
  notify,
}: UseWorkspaceConflictRecoveryOptions) {
  const generationRef = useRef(0)
  const [recovery, setRecovery] = useState<RecoveryState | null>(null)

  useEffect(() => {
    generationRef.current += 1
    setRecovery(null)
  }, [scopeKey])

  const runRecovery = async (successMessage: string, discardCurrentEditors: boolean) => {
    const generation = generationRef.current + 1
    generationRef.current = generation

    if (discardCurrentEditors) discardEditors()
    setRecovery({ generation, status: 'refreshing', successMessage })

    let refreshed: RefetchResult
    try {
      refreshed = await refetchWorkspace()
    } catch {
      if (generationRef.current !== generation) return false
      setRecovery({ generation, status: 'failed', successMessage })
      notify(refreshFailureMessage, 'error')
      return false
    }

    if (generationRef.current !== generation) return false

    if (refreshed.isSuccess) {
      setRecovery(null)
      notify(successMessage, 'error')
      return true
    }

    setRecovery({ generation, status: 'failed', successMessage })
    notify(refreshFailureMessage, 'error')
    return false
  }

  const beginRecovery = (successMessage: string) => {
    void runRecovery(successMessage, true)
  }

  const retryRecovery = () => {
    if (!recovery || recovery.status === 'refreshing') return
    void runRecovery(recovery.successMessage, false)
  }

  const ensureFreshWorkspace = () => {
    if (!recovery) return true

    notify('최신 기록을 확인해야 다시 수정할 수 있어요.', 'error')
    if (recovery.status === 'failed') retryRecovery()
    return false
  }

  return {
    recoveryStatus: recovery?.status,
    beginRecovery,
    retryRecovery,
    ensureFreshWorkspace,
  }
}
