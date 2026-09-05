import { useRef, useState } from 'react'
import { RecordDraftNotice, useRecordDraft } from './RecordDraft'
import type { WorkspaceScope } from './api'
import { DecisionText } from './records/DecisionText'
import type { FormEvent } from 'react'
import {
  CreationFormFeedback,
  FormActions,
  FormError,
  ModalShell,
  useSubmissionLock,
} from './WorkspaceModalPrimitives'
import type {
  CreationModalStatus,
  SaveResult,
} from './WorkspaceModalPrimitives'
import {
  categoryCopy,
  isActiveMember,
  memberSelectionOptions,
} from './workspacePresentation'
import type {
  CreateDecisionRequest,
  CreateHandoffItemRequest,
  CreateRoleResourceRequest,
  Decision,
  HandoffCategory,
  HandoffItem,
  Member,
  Role,
  RoleResource,
  UpdateDecisionRequest,
  UpdateHandoffItemRequest,
  UpdateRoleResourceRequest,
} from './types'

export type RoleResourceFormRequest = CreateRoleResourceRequest & UpdateRoleResourceRequest
export type DecisionFormRequest = CreateDecisionRequest & UpdateDecisionRequest
export type HandoffItemFormRequest = CreateHandoffItemRequest & UpdateHandoffItemRequest

export function DecisionModal({
  draftScope,
  roles,
  members,
  selectedRoleId,
  decision,
  pending,
  error,
  storageError,
  recoveryAvailable,
  onClose,
  onSave,
}: CreationModalStatus & {
  draftScope: WorkspaceScope | null
  roles: Role[]
  members: Member[]
  selectedRoleId: string
  decision?: Decision
  onClose: () => void
  onSave: (decision: DecisionFormRequest) => SaveResult
}) {
  const editing = Boolean(decision)
  const submission = useSubmissionLock(pending)
  const [title, setTitle] = useState(decision?.title ?? '')
  const [textFormat, setTextFormat] = useState<'PLAIN_TEXT' | 'MARKDOWN'>(decision?.textFormat ?? 'PLAIN_TEXT')
  const [preview, setPreview] = useState(false)
  const [reason, setReason] = useState(decision?.reason ?? '')
  const [alternative, setAlternative] = useState(decision?.alternative ?? '')
  const [roleIds, setRoleIds] = useState<string[]>(
    decision?.roleIds.length
      ? decision.roleIds
      : [selectedRoleId || roles[0]?.id || ''].filter(Boolean),
  )
  const [authorMemberId, setAuthorMemberId] = useState(
    decision?.authorMemberId ?? members.find(isActiveMember)?.id ?? '',
  )
  const draft = useRecordDraft(draftScope, 'decision', decision?.id ?? 'new', { title, reason, alternative, textFormat })
  const authorOptions = memberSelectionOptions(members, decision?.authorMemberId)
  const existingAuthor = decision
    ? members.find((member) => member.id === decision.authorMemberId)
    : undefined
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!title.trim() || !reason.trim() || !roleIds.length || !authorMemberId
      || submission.closeGuardRef.current) return
    submission.start(onSave({
      title: title.trim(),
      ...(textFormat === 'MARKDOWN' || decision?.textFormat === 'MARKDOWN' ? { textFormat } : {}),
      reason: reason.trim(),
      alternative: alternative.trim() || (editing ? '' : '별도 대안을 검토하지 않음'),
      authorMemberId,
      roleIds,
    }))
  }
  return (
    <ModalShell
      title={editing ? '결정 기록 수정' : '결정과 이유 남기기'}
      description={editing
        ? '잘못 적은 내용과 작성자, 관련 역할을 바로잡습니다. 처음 기록한 시각은 그대로 남아요.'
        : '결정한 내용과 이유, 검토한 대안을 적어 주세요.'}
      closeDisabled={submission.pending}
      closeGuardRef={submission.closeGuardRef}
      onClose={onClose}
    >
      <form className="modal-form" onSubmit={submit}>
        <RecordDraftNotice draft={draft} pending={submission.pending} onRestore={value => {
          setTitle(value.title ?? ''); setReason(value.reason ?? ''); setAlternative(value.alternative ?? '')
          setTextFormat(value.textFormat === 'MARKDOWN' ? 'MARKDOWN' : 'PLAIN_TEXT')
        }} />
        <label>
          <span>무엇을 바꾸기로 했나요?</span>
          <input
            autoFocus
            required
            value={title}
            onChange={(event) => setTitle(event.target.value)}
            placeholder="예: 세션 시작 시간을 30분 앞당긴다"
          />
        </label>
        <fieldset className="decision-text-editor">
          <legend>결정 이유와 대안</legend>
          <label>
            <span>본문 형식</span>
            <select value={textFormat} onChange={(event) => setTextFormat(event.target.value as 'PLAIN_TEXT' | 'MARKDOWN')}>
              <option value="PLAIN_TEXT">일반 텍스트</option>
              <option value="MARKDOWN">Markdown</option>
            </select>
          </label>
          <button type="button" className="decision-preview-toggle" aria-pressed={preview}
            onClick={() => setPreview(!preview)}>{preview ? '본문 편집' : '미리보기'}</button>
          {preview ? <div className="decision-preview" aria-label="결정 본문 미리보기">
            <strong>이유</strong><DecisionText text={reason || '이유를 입력해 주세요.'} format={textFormat} />
            <strong>검토한 다른 선택</strong><DecisionText text={alternative || '아직 대안을 입력하지 않았습니다.'} format={textFormat} />
          </div> : <>
            <label><span>왜 이 선택을 했나요?</span>
              <textarea required maxLength={2000} value={reason} onChange={(event) => setReason(event.target.value)}
                placeholder="반복된 문제나 관찰한 근거를 적어주세요" rows={4} />
            </label>
            <label><span>검토한 다른 선택</span>
              <textarea maxLength={2000} value={alternative} onChange={(event) => setAlternative(event.target.value)}
                placeholder="예: 세션 시간을 30분 연장하기" rows={3} />
            </label>
          </>}
          {textFormat === 'MARKDOWN' && <small>굵게, 목록, 링크와 코드 서식을 쓸 수 있어요. HTML·이미지·외부 임베드는 표시하지 않습니다.</small>}
        </fieldset>
        <label>
          <span>작성자</span>
          <select
            required
            value={authorMemberId}
            onChange={(event) => setAuthorMemberId(event.target.value)}
          >
            {authorOptions.map((member) => (
              <option
                key={member.id}
                value={member.id}
                disabled={!isActiveMember(member)}
              >
                {isActiveMember(member)
                  ? member.name
                  : `${member.name} (활동 종료 · 기존 작성자)`}
              </option>
            ))}
          </select>
          {existingAuthor && !isActiveMember(existingAuthor) && (
            <small>활동을 종료한 기존 작성자는 유지할 수 있지만 새로 선택할 수는 없어요.</small>
          )}
        </label>
        <fieldset className="modal-choice-group">
          <legend>영향받는 역할</legend>
          <div className="modal-choice-list">
            {roles.map((role) => (
              <label key={role.id}>
                <input
                  type="checkbox"
                  checked={roleIds.includes(role.id)}
                  onChange={(event) => setRoleIds((current) =>
                    event.target.checked
                      ? [...current, role.id]
                      : current.filter((roleId) => roleId !== role.id))}
                />
                <span>{role.name}</span>
              </label>
            ))}
          </div>
          {!roleIds.length && <small className="form-hint">관련 역할을 하나 이상 선택해 주세요.</small>}
        </fieldset>
        {editing
          ? <FormError error={error} />
          : (
              <CreationFormFeedback
                error={error}
                storageError={storageError}
                recoveryAvailable={recoveryAvailable}
              />
            )}
        <FormActions
          pending={submission.pending}
          closeGuardRef={submission.closeGuardRef}
          submitLabel={editing ? '변경 저장' : '결정 기록하기'}
          pendingLabel={editing ? '결정 저장하는 중…' : '결정 기록하는 중…'}
          onClose={onClose}
        />
      </form>
    </ModalShell>
  )
}
export function RoleResourceModal({
  draftScope,
  roles,
  lockedRoleIds = new Set<string>(),
  selectedRoleId,
  resource,
  initialResource,
  pending,
  error,
  storageError,
  recoveryAvailable,
  onClose,
  onSave,
}: CreationModalStatus & {
  draftScope: WorkspaceScope | null
  roles: Role[]
  lockedRoleIds?: ReadonlySet<string>
  selectedRoleId: string
  resource?: RoleResource
  initialResource?: Pick<RoleResource, 'title' | 'url' | 'description'>
  onClose: () => void
  onSave: (request: RoleResourceFormRequest) => SaveResult
}) {
  const editing = Boolean(resource)
  const submission = useSubmissionLock(pending)
  const titleInputRef = useRef<HTMLInputElement>(null)
  const [roleId, setRoleId] = useState(
    (resource?.roleId
      ?? (lockedRoleIds.has(selectedRoleId) ? '' : selectedRoleId))
      || roles.find((role) => !lockedRoleIds.has(role.id))?.id
      || '',
  )
  const [title, setTitle] = useState(resource?.title ?? initialResource?.title ?? '')
  const [url, setUrl] = useState(resource?.url ?? initialResource?.url ?? '')
  const [description, setDescription] = useState(resource?.description ?? initialResource?.description ?? '')
  const draft = useRecordDraft(draftScope, 'resource', resource?.id ?? 'new', { title, url, description })
  const [titleValidationMessage, setTitleValidationMessage] = useState('')
  const [urlValidationMessage, setUrlValidationMessage] = useState('')
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (submission.closeGuardRef.current || !roleId || lockedRoleIds.has(roleId)) return
    if (!title.trim()) {
      setTitleValidationMessage('자료 이름을 입력해 주세요.')
      titleInputRef.current?.focus()
      return
    }
    const normalizedUrl = url.trim()
    const parsed = URL.parse(normalizedUrl)
    if (!parsed
      || (parsed.protocol !== 'http:' && parsed.protocol !== 'https:')
      || !parsed.hostname
      || parsed.username
      || parsed.password) {
      setUrlValidationMessage('사용자 정보 없이 http 또는 https로 시작하는 전체 링크를 입력해 주세요.')
      return
    }
    setTitleValidationMessage('')
    setUrlValidationMessage('')
    submission.start(onSave({
      roleId,
      title: title.trim(),
      url: normalizedUrl,
      description: description.trim() || null,
    }))
  }
  return (
    <ModalShell
      title={editing ? '참고 자료 수정' : '역할에 참고 자료 연결'}
      description={initialResource
        ? '이전 시즌 자료를 현재 역할에 새로 연결합니다. 제목과 링크를 확인해 저장해 주세요. 이전 시즌 기록은 유지됩니다.'
        : '문서나 외부 링크를 역할에 연결해, 담당자가 바뀌어도 같은 자료를 바로 찾게 합니다.'}
      closeDisabled={submission.pending}
      closeGuardRef={submission.closeGuardRef}
      initialFocusRef={titleInputRef}
      onClose={onClose}
    >
      <form className="modal-form" noValidate onSubmit={submit}>
        <RecordDraftNotice draft={draft} pending={submission.pending} onRestore={value => {
          setTitle(value.title ?? ''); setUrl(value.url ?? ''); setDescription(value.description ?? '')
        }} />
        <label>
          <span>역할</span>
          <select required value={roleId} onChange={(event) => setRoleId(event.target.value)}>
            {roles.map((role) => (
              <option key={role.id} value={role.id} disabled={lockedRoleIds.has(role.id)}>
                {role.name}{lockedRoleIds.has(role.id) ? ' · 수락 대기' : ''}
              </option>
            ))}
          </select>
        </label>
        {lockedRoleIds.has(roleId) && (
          <p className="form-error" role="alert">
            전달한 역할은 수락하거나 취소한 뒤 자료를 수정할 수 있어요.
          </p>
        )}
        <label>
          <span>자료 이름</span>
          <input
            ref={titleInputRef}
            autoFocus
            required
            maxLength={200}
            aria-invalid={Boolean(titleValidationMessage)}
            aria-describedby={titleValidationMessage ? 'role-resource-title-error' : undefined}
            value={title}
            onChange={(event) => {
              setTitle(event.target.value)
              setTitleValidationMessage('')
            }}
            placeholder="예: 질문 정리 가이드"
          />
        </label>
        {titleValidationMessage && (
          <p id="role-resource-title-error" className="form-error" role="alert">
            {titleValidationMessage}
          </p>
        )}
        <label>
          <span>링크</span>
          <input
            type="url"
            required
            maxLength={2048}
            autoCapitalize="none"
            spellCheck={false}
            aria-invalid={Boolean(urlValidationMessage)}
            aria-describedby={urlValidationMessage ? 'role-resource-url-error' : undefined}
            value={url}
            onChange={(event) => {
              setUrl(event.target.value)
              setUrlValidationMessage('')
            }}
            placeholder="https://docs.example.com/guide"
          />
        </label>
        <label>
          <span>자료 설명</span>
          <textarea
            maxLength={1000}
            value={description}
            onChange={(event) => setDescription(event.target.value)}
            placeholder="이 자료를 언제, 어떻게 사용하는지 적어주세요"
            rows={3}
          />
        </label>
        {urlValidationMessage && (
          <p id="role-resource-url-error" className="form-error" role="alert">
            {urlValidationMessage}
          </p>
        )}
        {editing
          ? <FormError error={error} />
          : (
              <CreationFormFeedback
                error={error}
                storageError={storageError}
                recoveryAvailable={recoveryAvailable}
              />
            )}
        <FormActions
          pending={submission.pending}
          closeGuardRef={submission.closeGuardRef}
          submitDisabled={lockedRoleIds.has(roleId)}
          submitLabel={editing ? '변경 저장' : '자료 연결하기'}
          pendingLabel={editing ? '자료 저장하는 중…' : '자료 연결하는 중…'}
          onClose={onClose}
        />
      </form>
    </ModalShell>
  )
}

export function HandoffItemModal({
  draftScope,
  roles,
  lockedRoleIds = new Set<string>(),
  selectedRoleId,
  item,
  pending,
  error,
  storageError,
  recoveryAvailable,
  onClose,
  onSave,
}: CreationModalStatus & {
  draftScope: WorkspaceScope | null
  roles: Role[]
  lockedRoleIds?: ReadonlySet<string>
  selectedRoleId: string
  item?: HandoffItem
  onClose: () => void
  onSave: (item: HandoffItemFormRequest) => SaveResult
}) {
  const editing = Boolean(item)
  const submission = useSubmissionLock(pending)
  const [roleId, setRoleId] = useState(
    (item?.roleId
      ?? (lockedRoleIds.has(selectedRoleId) ? '' : selectedRoleId))
      || roles.find((role) => !lockedRoleIds.has(role.id))?.id
      || '',
  )
  const [label, setLabel] = useState(item?.label ?? '')
  const [category, setCategory] = useState<HandoffCategory>(
    item?.category ?? 'RESPONSIBILITY',
  )
  const draft = useRecordDraft(draftScope, 'handoff', item?.id ?? 'new', { label })
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (submission.closeGuardRef.current || !roleId || lockedRoleIds.has(roleId)) return
    submission.start(onSave({ roleId, label: label.trim(), category }))
  }
  return (
    <ModalShell
      title={editing ? '인수인계 항목 수정' : '인수인계 항목 추가'}
      description={editing
        ? '잘못 적은 역할, 내용이나 분류를 고칩니다. 준비 완료 표시는 그대로 유지돼요.'
        : '다음 담당자가 바로 움직이려면 꼭 알아야 할 내용 하나를 남겨주세요.'}
      closeDisabled={submission.pending}
      closeGuardRef={submission.closeGuardRef}
      onClose={onClose}
    >
      <form className="modal-form" onSubmit={submit}>
        <RecordDraftNotice draft={draft} pending={submission.pending} onRestore={value => setLabel(value.label ?? '')} />
        <label>
          <span>역할</span>
          <select required value={roleId} onChange={(event) => setRoleId(event.target.value)}>
            {roles.map((role) => (
              <option key={role.id} value={role.id} disabled={lockedRoleIds.has(role.id)}>
                {role.name}{lockedRoleIds.has(role.id) ? ' · 수락 대기' : ''}
              </option>
            ))}
          </select>
        </label>
        {lockedRoleIds.has(roleId) && (
          <p className="form-error" role="alert">
            전달한 역할은 수락하거나 취소한 뒤 인수인계 문서를 수정할 수 있어요.
          </p>
        )}
        <label>
          <span>남길 내용</span>
          <input
            autoFocus
            required
            value={label}
            onChange={(event) => setLabel(event.target.value)}
            placeholder="예: 문제 선정 기준 문서 링크"
          />
        </label>
        <label>
          <span>항목 종류</span>
          <select
            value={category}
            onChange={(event) => setCategory(event.target.value as HandoffCategory)}
          >
            {(Object.keys(categoryCopy) as HandoffCategory[]).map((value) => (
              <option key={value} value={value}>{categoryCopy[value]}</option>
            ))}
          </select>
        </label>
        {editing
          ? <FormError error={error} />
          : (
              <CreationFormFeedback
                error={error}
                storageError={storageError}
                recoveryAvailable={recoveryAvailable}
              />
            )}
        <FormActions
          pending={submission.pending}
          closeGuardRef={submission.closeGuardRef}
          submitDisabled={lockedRoleIds.has(roleId)}
          submitLabel={editing ? '변경 저장' : '항목 추가하기'}
          pendingLabel={editing ? '항목 저장하는 중…' : '항목 추가하는 중…'}
          onClose={onClose}
        />
      </form>
    </ModalShell>
  )
}
