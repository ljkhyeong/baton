import { useEffect, useRef, useState } from 'react'
import type { WorkspaceScope } from './api'
import { isJsonObject } from '@/shared/api/responseValidation'

export type RecordDraftKind = 'decision' | 'resource' | 'handoff'
type Fields = Record<string, string>
type StoredDraft = { version: 1; savedAt: number; base: string; fields: Fields }
const MAX_AGE = 24 * 60 * 60 * 1000

async function draftKey(scope: WorkspaceScope, kind: RecordDraftKind, id: string) {
  const context = JSON.stringify([scope.accountId ?? '', scope.teamId, scope.seasonId, scope.accessKey, kind, id])
  const hash = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(context))
  return `baton:record-draft:v1:${Array.from(new Uint8Array(hash), value => value.toString(16).padStart(2, '0')).join('')}`
}

export async function clearRecordDraft(scope: WorkspaceScope, kind: RecordDraftKind, id: string) {
  window.sessionStorage.removeItem(await draftKey(scope, kind, id))
}

export function useRecordDraft(scope: WorkspaceScope | null, kind: RecordDraftKind, id: string, fields: Fields) {
  const serialized = JSON.stringify(fields)
  const initial = useRef(serialized)
  const [key, setKey] = useState<string | null>(null)
  const [saved, setSaved] = useState<StoredDraft | null>(null)
  const [message, setMessage] = useState('')
  const [available, setAvailable] = useState(false)
  const ignored = useRef(initial.current)
  const scopeValue = JSON.stringify([scope?.accountId, scope?.teamId, scope?.seasonId, scope?.accessKey, kind, id])
  useEffect(() => {
    if (!scope) {
      setKey(null); setSaved(null); setAvailable(false)
      setMessage('로그인 상태를 확인한 뒤 초안을 저장할 수 있습니다.')
      return
    }
    let current = true
    void draftKey(scope, kind, id).then(storageKey => {
      if (!current) return
      try {
        const raw = sessionStorage.getItem(storageKey)
        if (raw) {
          let value: unknown = null
          try { if (raw.length <= 30_000) value = JSON.parse(raw) } catch { /* 손상된 초안은 아래에서 제거한다. */ }
          const names = Object.keys(JSON.parse(initial.current) as Fields)
          if (isJsonObject(value) && value.version === 1 && typeof value.savedAt === 'number'
            && value.savedAt <= Date.now() && Date.now() - value.savedAt <= MAX_AGE
            && typeof value.base === 'string' && isJsonObject(value.fields)
            && Object.keys(value.fields).length === names.length
            && names.every(name => typeof (value.fields as Fields)[name] === 'string' && (value.fields as Fields)[name]!.length <= 6000)) {
            setSaved(value as StoredDraft)
            setAvailable(true)
          } else sessionStorage.removeItem(storageKey)
        }
        setKey(storageKey)
      } catch {
        setMessage('탭 저장소를 사용할 수 없어 초안을 복구하거나 저장하지 못합니다. 입력 내용을 따로 복사해 주세요.')
      }
    }).catch(() => { if (current) setMessage('이 브라우저에서는 초안 저장을 사용할 수 없습니다.') })
    return () => { current = false }
    // 범위가 바뀌면 부모가 편집 창을 닫거나 다시 생성한다.
  }, [scopeValue])

  useEffect(() => {
    if (!key || serialized === ignored.current) return
    try {
      if (serialized === initial.current) {
        sessionStorage.removeItem(key)
        ignored.current = serialized
        setSaved(null); setAvailable(false)
        setMessage('처음 내용으로 되돌려 저장된 초안을 지웠습니다.')
        return
      }
      const value: StoredDraft = { version: 1, savedAt: Date.now(), base: initial.current, fields: JSON.parse(serialized) as Fields }
      sessionStorage.setItem(key, JSON.stringify(value))
      ignored.current = serialized
      setSaved(value)
      setAvailable(false)
      setMessage('현재 탭에 초안을 저장했습니다.')
    } catch {
      setMessage('초안을 저장하지 못했습니다. 페이지를 나가기 전에 입력 내용을 복사해 주세요.')
    }
  }, [key, serialized])

  const discard = () => {
    if (!key) return
    try {
      sessionStorage.removeItem(key)
      ignored.current = serialized
      setSaved(null); setAvailable(false); setMessage('저장된 초안을 지웠습니다. 현재 입력 내용은 유지합니다.')
    } catch { setMessage('저장된 초안을 지우지 못했습니다.') }
  }
  return { available, saved, message, discard, restored: () => setAvailable(false),
    changedOriginal: Boolean(saved && saved.base !== initial.current) }
}

export function RecordDraftNotice({ draft, pending, onRestore }: {
  draft: ReturnType<typeof useRecordDraft>; pending: boolean; onRestore: (fields: Fields) => void
}) {
  return <aside className="record-draft" aria-label="작성 중 초안">
    <small>현재 탭에 본문 초안을 24시간 보관합니다. 페이지를 다시 열고 같은 작성 창에서 불러올 수 있습니다. 작성자·역할은 현재 선택을 확인해 주세요.</small>
    {draft.available && draft.saved && <>
      <p>이전에 작성하던 초안이 있습니다.{draft.changedOriginal && ' 원본이 변경되었습니다. 최신 내용과 비교해 주세요.'}</p>
      <button type="button" className="secondary-button" disabled={pending} onClick={() => {
        onRestore(draft.saved!.fields); draft.restored()
      }}>초안 불러오기</button>
    </>}
    {draft.saved && <button type="button" className="text-button" disabled={pending} onClick={draft.discard}>저장된 초안 삭제</button>}
    {draft.message && <p role="status">{draft.message}</p>}
  </aside>
}
