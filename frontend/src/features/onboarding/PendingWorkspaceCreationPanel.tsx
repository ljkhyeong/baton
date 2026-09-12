import { workspaceTemplates } from './workspaceTemplates'
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
  if (Number.isNaN(date.getTime())) return '저장 시각 없음'
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

const loadedNoticeMessage = '저장된 입력을 불러왔습니다. 생성 코드가 필요하면 입력한 뒤 ‘생성 결과 다시 확인’을 누르세요.'
const discardBusyMessage = '다른 탭에서 생성 결과를 확인하고 있습니다. 끝난 뒤 다시 시도해 주세요.'

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
    setNotice((current) => current === loadedNoticeMessage ? '' : current)
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
        ? '목록에서 삭제했습니다. 생성된 작업 공간은 그대로 유지됩니다.'
        : '다른 탭에서 이미 삭제한 임시 기록입니다.')
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
      setError('이 브라우저에서는 임시 기록을 처리할 수 없습니다. 브라우저를 업데이트한 뒤 다시 시도해 주세요.')
      return
    }
    if (result === 'changed') {
      setError('다른 탭에서 임시 기록이 변경됐습니다. 최신 목록을 확인해 주세요.')
      onRefresh()
      return
    }
    setError('목록에서 삭제하지 못했습니다. 사이트 데이터 저장을 허용한 뒤 다시 시도해 주세요.')
  }

  const load = (item: PendingWorkspaceCreationItem) => {
    setLoadedItem(item)
    setNotice(loadedNoticeMessage)
    setError('')
    onLoad(item)
  }

  return (
    <section
      className="pending-workspaces-region"
      aria-label="생성 결과 확인 필요"
    >
      {items.length > 0 && (
        <details
          className="pending-workspaces"
          open={open}
          onToggle={(event) => setOpen(event.currentTarget.open)}
        >
          <summary>
            <span>생성 결과 확인 필요 <strong>{items.length}개</strong></span>
            <small>작업 공간이 만들어졌는지 확인하세요</small>
          </summary>
          <p className="pending-workspaces-intro">
            생성 결과를 받지 못했습니다. 저장된 입력을 불러와 다시 확인하세요. 이미 만들어졌다면 기존 작업 공간을 엽니다.
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
                    <span>{item.request.template ? workspaceTemplates[item.request.template].name : '템플릿 없음'}</span>
                    <small>
                      {formatLocalDate(item.request.startDate)}–{formatLocalDate(item.request.endDate)}
                      {' · '}
                      구성원 {item.request.memberNames.length}명
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
                      {selected ? '입력 불러오기 완료' : '입력 불러오기'}
                    </button>
                    <button
                      type="button"
                      className="text-button"
                      disabled={busy || discardingId !== null}
                      onClick={() => openConfirmation(item)}
                      aria-expanded={confirming}
                      aria-controls={confirming ? confirmationId : undefined}
                      aria-label={`${label} 목록에서 지우기`}
                      ref={(element) => {
                        if (element) discardButtonRefs.current.set(item.idempotencyKey, element)
                        else discardButtonRefs.current.delete(item.idempotencyKey)
                      }}
                    >
                      목록에서 지우기
                    </button>
                  </div>

                  {confirming && (
                    <div
                      id={confirmationId}
                      className="pending-workspace-confirmation"
                      role="group"
                      aria-labelledby={confirmationTitleId}
                    >
                      <strong id={confirmationTitleId}>목록에서 삭제할까요?</strong>
                      <p>
                        작업 공간은 삭제되지 않습니다. 다만 이 브라우저에서 공유 링크를 다시 찾지 못할 수 있습니다.
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
                          {discardingId === item.idempotencyKey ? '삭제하는 중…' : '지우기'}
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
