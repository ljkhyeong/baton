import { useEffect, useId, useRef, useState } from 'react'
import {
  discardPendingWorkspaceCreation,
  isSamePendingWorkspaceCreationItem,
} from './pendingWorkspaceCreation'
import type {
  PendingWorkspaceCreationItem,
} from './pendingWorkspaceCreation'

type PendingWorkspaceCreationPanelProps = {
  items: readonly PendingWorkspaceCreationItem[]
  busy: boolean
  selectedItem: PendingWorkspaceCreationItem | null
  onLoad: (item: PendingWorkspaceCreationItem) => void
  onRefresh: () => void
  onFocusForm: () => void
}

function formatCreatedAt(value: number) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '저장 시각 확인 불가'
  return new Intl.DateTimeFormat('ko-KR', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

function formatLocalDate(value: string) {
  return value.replaceAll('-', '.')
}

const legacyLoadedNoticeMessage = '저장된 입력을 불러왔습니다. 필요한 생성 코드를 입력한 뒤 같은 생성 결과를 확인해 주세요.'
const sessionLoadedNoticeMessage = '저장된 입력을 불러왔습니다. 같은 계정과 OWNER 선택으로 결과를 다시 확인해 주세요.'
const discardBusyMessage = '다른 탭에서 작업 공간 생성 결과를 확인 중입니다. 처리가 끝난 뒤 다시 시도해 주세요.'

function loadedNoticeMessage(item: PendingWorkspaceCreationItem) {
  return item.request.mode === 'session'
    ? sessionLoadedNoticeMessage
    : legacyLoadedNoticeMessage
}

export default function PendingWorkspaceCreationPanel({
  items,
  busy,
  selectedItem,
  onLoad,
  onRefresh,
  onFocusForm,
}: PendingWorkspaceCreationPanelProps) {
  const confirmationId = useId()
  const confirmationTitleId = useId()
  const [open, setOpen] = useState(false)
  const [confirmingId, setConfirmingId] = useState<string | null>(null)
  const [discardingId, setDiscardingId] = useState<string | null>(null)
  const [loadedItem, setLoadedItem] = useState<PendingWorkspaceCreationItem | null>(null)
  const [notice, setNotice] = useState('')
  const [error, setError] = useState('')
  const discardButtonRefs = useRef(new Map<string, HTMLButtonElement>())
  const keepButtonRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (items.length >= 5) setOpen(true)
  }, [items.length])

  useEffect(() => {
    if (confirmingId && !items.some((item) => item.idempotencyKey === confirmingId)) {
      setConfirmingId(null)
      setError((current) => current === discardBusyMessage ? '' : current)
    }
  }, [confirmingId, items])

  useEffect(() => {
    if (!loadedItem
      || (
        selectedItem !== null
        && isSamePendingWorkspaceCreationItem(selectedItem, loadedItem)
        && items.some((item) => isSamePendingWorkspaceCreationItem(item, loadedItem))
      )) {
      return
    }
    setLoadedItem(null)
    setNotice((current) => (
      current === legacyLoadedNoticeMessage || current === sessionLoadedNoticeMessage
        ? ''
        : current
    ))
    setError((current) => current === discardBusyMessage ? '' : current)
  }, [items, loadedItem, selectedItem])

  const openConfirmation = (item: PendingWorkspaceCreationItem) => {
    setNotice('')
    setError('')
    setConfirmingId(item.idempotencyKey)
    requestAnimationFrame(() => keepButtonRef.current?.focus())
  }

  const closeConfirmation = (item: PendingWorkspaceCreationItem) => {
    setConfirmingId(null)
    requestAnimationFrame(() => discardButtonRefs.current.get(item.idempotencyKey)?.focus())
  }

  const discard = async (item: PendingWorkspaceCreationItem) => {
    const index = items.findIndex((candidate) => candidate.idempotencyKey === item.idempotencyKey)
    const nextItem = items[index + 1] ?? items[index - 1]
    setDiscardingId(item.idempotencyKey)
    setNotice('')
    setError('')
    const result = await discardPendingWorkspaceCreation(item)
    setDiscardingId(null)

    if (result === 'discarded' || result === 'missing') {
      setConfirmingId(null)
      setNotice(result === 'discarded'
        ? '복구 기록을 폐기했습니다. 서버에 이미 만들어진 작업 공간은 삭제되지 않았습니다.'
        : '다른 탭에서 이미 정리한 복구 기록입니다.')
      onRefresh()
      requestAnimationFrame(() => {
        if (nextItem) {
          discardButtonRefs.current.get(nextItem.idempotencyKey)?.focus()
        } else {
          onFocusForm()
        }
      })
      return
    }

    if (result === 'busy') {
      setError(discardBusyMessage)
      return
    }
    if (result === 'unsupported') {
      setError('이 브라우저에서는 복구 기록을 안전하게 처리할 수 없습니다. 브라우저를 최신 버전으로 업데이트하거나 다른 브라우저에서 결과 확인 또는 폐기를 진행해 주세요.')
      return
    }
    if (result === 'changed') {
      setError('다른 탭에서 복구 기록이 변경됐습니다. 최신 목록을 확인해 주세요.')
      onRefresh()
      return
    }
    setError('브라우저 저장소에서 복구 기록을 폐기하지 못했습니다. 저장소 권한을 확인해 주세요.')
  }

  const load = (item: PendingWorkspaceCreationItem) => {
    setLoadedItem(item)
    setNotice(loadedNoticeMessage(item))
    setError('')
    onLoad(item)
  }

  return (
    <section
      className="pending-workspaces-region"
      aria-label="확인되지 않은 작업 공간 생성 요청"
    >
      {items.length > 0 && (
        <details
          className="pending-workspaces"
          open={open}
          onToggle={(event) => setOpen(event.currentTarget.open)}
        >
          <summary>
            <span>확인하지 못한 생성 요청 <strong>{items.length}개</strong></span>
            <small>복구 대기</small>
          </summary>
          <p className="pending-workspaces-intro">
            완료 여부를 확인하지 못한 요청입니다. 저장된 입력과 같은 키로 다시 제출하면
            아직 처리 전인 생성을 계속하거나 이미 처리된 결과를 확인합니다.
          </p>
          <ul>
            {items.map((item) => {
              const confirming = confirmingId === item.idempotencyKey
              const selected = selectedItem !== null
                && isSamePendingWorkspaceCreationItem(selectedItem, item)
              const label = `${item.request.teamName} ${item.request.seasonName}`
              return (
                <li key={item.idempotencyKey}>
                  <div className="pending-workspace-copy">
                    <strong>{item.request.teamName}</strong>
                    <span>{item.request.seasonName}</span>
                    <small>
                      {formatLocalDate(item.request.startDate)}–{formatLocalDate(item.request.endDate)}
                      {' · '}
                      구성원 {item.request.memberNames.length}명
                      {item.request.mode === 'session'
                        ? ` · OWNER ${item.request.ownerMemberName}`
                        : ' · 레거시 공유 키'}
                      {' · '}
                      {formatCreatedAt(item.createdAt)} 기록
                    </small>
                  </div>
                  <div className="pending-workspace-actions">
                    <button
                      type="button"
                      className="secondary-button"
                      disabled={busy || discardingId !== null}
                      onClick={() => load(item)}
                      aria-label={`${label} 저장된 입력 불러오기`}
                    >
                      {selected ? '입력 불러옴' : '입력 불러오기'}
                    </button>
                    <button
                      type="button"
                      className="text-button"
                      disabled={busy || discardingId !== null}
                      onClick={() => openConfirmation(item)}
                      aria-expanded={confirming}
                      aria-controls={confirming ? confirmationId : undefined}
                      aria-label={`${label} 복구 기록 폐기`}
                      ref={(element) => {
                        if (element) discardButtonRefs.current.set(item.idempotencyKey, element)
                        else discardButtonRefs.current.delete(item.idempotencyKey)
                      }}
                    >
                      복구 기록 폐기
                    </button>
                  </div>

                  {confirming && (
                    <div
                      id={confirmationId}
                      className="pending-workspace-confirmation"
                      role="group"
                      aria-labelledby={confirmationTitleId}
                    >
                      <strong id={confirmationTitleId}>이 복구 기록을 폐기할까요?</strong>
                      <p>
                        {item.request.mode === 'session'
                          ? '서버에서 이미 처리된 요청이라면 같은 계정으로 로그인해 작업 공간을 다시 확인한 뒤 폐기해야 합니다.'
                          : '서버에서 이미 처리된 요청이라면 폐기 후 이 브라우저에서 공유 키를 되찾지 못할 수 있습니다.'}
                        서버의 작업 공간 자체는 삭제되지 않습니다.
                      </p>
                      <div>
                        <button
                          type="button"
                          className="secondary-button"
                          disabled={discardingId !== null}
                          onClick={() => closeConfirmation(item)}
                          ref={keepButtonRef}
                        >
                          계속 보관
                        </button>
                        <button
                          type="button"
                          className="danger-button"
                          disabled={discardingId !== null}
                          onClick={() => void discard(item)}
                        >
                          {discardingId === item.idempotencyKey ? '폐기하는 중…' : '확인하고 폐기'}
                        </button>
                      </div>
                    </div>
                  )}
                </li>
              )
            })}
          </ul>
        </details>
      )}

      {notice && <p className="form-retry-notice" role="status">{notice}</p>}
      {error && <p className="form-error" role="alert">{error}</p>}
    </section>
  )
}
