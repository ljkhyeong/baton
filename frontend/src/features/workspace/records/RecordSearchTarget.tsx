import { useEffect, useRef } from 'react'
import { useSearchParams } from 'react-router-dom'
import type { WorkspaceProjection } from '../types'
import { initialRecordSearchFilters } from './RecordSearchView'
import { searchWorkspaceRecords, type RecordSearchResult } from './recordSearch'

export function RecordSearchTarget({ workspace, onOpenResult }: {
  workspace: WorkspaceProjection
  onOpenResult: (result: RecordSearchResult) => void
}) {
  const [params] = useSearchParams()
  const handled = useRef(false)
  useEffect(() => {
    if (handled.current) return
    handled.current = true
    const result = searchWorkspaceRecords(workspace, initialRecordSearchFilters, workspace.season.timeZone)
      .find(item => item.kind === params.get('recordKind') && item.id === params.get('recordId') && !item.archivedAt)
    if (result) onOpenResult(result)
  }, [params, workspace, onOpenResult])
  return null
}
