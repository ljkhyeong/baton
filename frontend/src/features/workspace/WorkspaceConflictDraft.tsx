import { useRef, useState } from 'react'
import { ApiError } from '@/shared/api/ApiError'

type ConflictDraft = { title: string; text: string }
type DraftField = readonly [label: string, value: string | number | null | undefined]
export type PreserveConflictDraft = <T>(submission: Promise<T>, title: string, fields: DraftField[]) => Promise<T>

export function useWorkspaceConflictDraft(scopeKey: string) {
  const [state, setState] = useState<{ scopeKey: string; generation: number; draft: ConflictDraft | null }>({
    scopeKey, generation: 0, draft: null,
  })
  if (state.scopeKey !== scopeKey) {
    setState({ scopeKey, generation: state.generation + 1, draft: null })
  }

  const preserve: PreserveConflictDraft = (submission, title, fields) => submission.catch((error: unknown) => {
    if (error instanceof ApiError && error.code === 'WORKSPACE_CONTENT_CONFLICT') {
      const text = fields.map(([label, value]) => `${label}\n${value === '' || value == null ? '미입력' : value}`).join('\n\n')
      setState((current) => current.scopeKey === scopeKey && current.generation === state.generation
        ? { ...current, draft: { title, text } }
        : current)
    }
    throw error
  })

  return {
    draft: state.scopeKey === scopeKey ? state.draft : null,
    preserve,
    discard: () => setState((current) => ({ ...current, draft: null })),
  }
}

export function WorkspaceConflictDraft({ draft, onDiscard }: {
  draft: ConflictDraft
  onDiscard: () => void
}) {
  const textAreaRef = useRef<HTMLTextAreaElement>(null)
  const [copyMessage, setCopyMessage] = useState('')
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(`${draft.title}\n\n${draft.text}`)
      setCopyMessage('입력 내용을 복사했습니다.')
    } catch {
      textAreaRef.current?.focus()
      textAreaRef.current?.select()
      setCopyMessage('자동 복사를 사용할 수 없습니다. 선택된 내용을 직접 복사해 주세요.')
    }
  }

  return <details className="conflict-draft" open>
    <summary>저장하지 못한 입력 내용 · {draft.title}</summary>
    <p>입력 내용을 복사한 뒤 최신 기록을 열어 다시 수정하세요. 아래 내용은 직접 수정할 수 없습니다.</p>
    <label>
      <span>저장하지 못한 입력 내용 (읽기 전용)</span>
      <textarea ref={textAreaRef} rows={6} readOnly value={draft.text} />
    </label>
    <div className="conflict-draft-actions">
      <button type="button" className="secondary-button" onClick={() => void copy()}>입력 내용 복사</button>
      <button type="button" className="secondary-button" onClick={onDiscard}>초안 버리기</button>
    </div>
    <p aria-live="polite">{copyMessage}</p>
    <small>새로고침하거나 계정·팀·시즌을 바꾸면 이 초안은 사라집니다. 다른 수정과 다시 충돌하거나 공유 링크·접근 권한이 바뀌어도 사라집니다.</small>
  </details>
}
