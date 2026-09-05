import { workspaceTemplates } from './workspaceTemplates'
import type { CreateWorkspaceRequest } from '@/features/workspace/types'
import { Link } from 'react-router-dom'
import PendingWorkspaceCreationPanel from './PendingWorkspaceCreationPanel'
import { useOnboardingWorkspaceFlow } from './useOnboardingWorkspaceFlow'
import {
  MAX_INITIAL_MEMBER_COUNT,
  MAX_MEMBER_NAME_LENGTH,
  MAX_WORKSPACE_NAME_LENGTH,
} from './workspaceCreationConstraints'

function formatLastOpenedAt(value: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

export default function OnboardingForm() {
  const {
    teamNameInputRef,
    form,
    pending,
    creation,
    recentWorkspaces,
    forgetWorkspace,
  } = useOnboardingWorkspaceFlow()

  return (
    <main className="onboarding-page">
      <section className="onboarding-story" aria-labelledby="onboarding-title">
        <div className="brand onboarding-brand"><span className="brand-mark" />BATON</div>
        <div>
          <span className="section-kicker">첫 번째 바통</span>
          <h1 id="onboarding-title">사람이 바뀌어도<br />운영은 이어지게.</h1>
          <p>스터디의 역할, 반복 운영, 결정의 이유와 다음 담당자에게 넘길 맥락을 한곳에 남겨보세요.</p>
        </div>
        <ol className="onboarding-points">
          <li><span>01</span><strong>시즌을 열고</strong><small>함께할 기간과 구성원을 정합니다.</small></li>
          <li><span>02</span><strong>역할을 세우고</strong><small>반복되는 책임을 사람과 분리합니다.</small></li>
          <li><span>03</span><strong>바통을 남겨요</strong><small>다음 사람이 바로 움직일 맥락을 모읍니다.</small></li>
        </ol>
      </section>

      <section className="onboarding-form-panel" aria-labelledby="workspace-form-title">
        <div className="onboarding-form-topline">
          <div className="onboarding-form-heading">
            <span className="section-kicker">새 작업 공간</span>
            <h2 id="workspace-form-title">우리 스터디를 시작해요</h2>
            <p>지금 입력한 정보로 첫 시즌과 공유 작업 공간을 만듭니다.</p>
          </div>
          <div><Link className="onboarding-login-link" to="/my-teams">내 팀</Link> · <Link className="onboarding-login-link" to="/login">계정 로그인</Link></div>
        </div>

        <PendingWorkspaceCreationPanel
          items={pending.items}
          busy={creation.busy}
          selectedItem={pending.selectedItem}
          onLoad={pending.load}
          onRefresh={pending.refresh}
          onFocusForm={() => teamNameInputRef.current?.focus()}
        />

        {creation.confirmationReason && (
          <div className="form-retry-notice pending-recovery-stale" role="status">
            <p>{creation.confirmationMessage}</p>
            {creation.confirmationReason === 'cleanupRequired'
              ? (
                  <button
                    type="button"
                    className="text-button"
                    disabled={creation.busy}
                    onClick={() => void creation.retryJournalCleanup()}
                  >
                    완료 기록 정리 다시 확인
                  </button>
                )
              : (
                  <button
                    type="button"
                    className="text-button"
                    onClick={creation.startNewRequest}
                  >
                    기존 결과를 확인했고 새 요청으로 전환
                  </button>
                )}
          </div>
        )}

        {recentWorkspaces.length > 0 && (
          <section className="recent-workspaces" aria-labelledby="recent-workspaces-title">
            <div className="recent-workspaces-heading">
              <h3 id="recent-workspaces-title">최근 작업 공간</h3>
              <span>이 브라우저에서 열었던 공간</span>
            </div>
            <ul>
              {recentWorkspaces.map((workspace) => {
                const path = `/teams/${encodeURIComponent(workspace.teamId)}/seasons/${encodeURIComponent(workspace.seasonId)}`
                return (
                  <li key={`${workspace.teamId}:${workspace.seasonId}`}>
                    <Link to={path}>
                      <span><strong>{workspace.teamName}</strong><small>{workspace.seasonName}</small></span>
                      <time dateTime={workspace.lastOpenedAt}>
                        {formatLastOpenedAt(workspace.lastOpenedAt)}
                      </time>
                    </Link>
                    <button
                      type="button"
                      onClick={() => forgetWorkspace(workspace)}
                      aria-label={`${workspace.teamName} ${workspace.seasonName} 이 기기에서 접근 권한 제거`}
                    >
                      이 기기 권한 제거
                    </button>
                  </li>
                )
              })}
            </ul>
          </section>
        )}

        <form className="onboarding-form" onSubmit={form.submit}>
          <label><span>시작 구성</span><select value={form.template ?? ''} disabled={creation.busy}
            onChange={event => form.setTemplate((event.target.value || undefined) as CreateWorkspaceRequest['template'])}>
            <option value="">빈 구성 · 역할과 루틴을 직접 만들기</option>
            {Object.entries(workspaceTemplates).map(([id, template]) => <option key={id} value={id}>{template.name}</option>)}
          </select></label>
          {form.template && <section className="workspace-template-preview" aria-label="시작 구성 미리보기">
            <h3>{workspaceTemplates[form.template].name}</h3>
            <p>역할: {workspaceTemplates[form.template].roles.join(' · ')}</p>
            <p>루틴: {workspaceTemplates[form.template].routines.join(' · ')}</p>
            <small>역할 3개와 루틴 3개를 함께 만듭니다. 담당자·실제 마감·반복 일정은 만든 뒤 정해 주세요. 생성한 내용은 수정할 수 있습니다.</small>
          </section>}
          <label>
            <span>팀 이름</span>
            <input
              required
              autoFocus
              ref={teamNameInputRef}
              maxLength={MAX_WORKSPACE_NAME_LENGTH}
              value={form.teamName}
              onChange={(event) => form.setTeamName(event.target.value)}
              placeholder="예: 알고리즘 한 바퀴"
            />
          </label>
          <label>
            <span>시즌 이름</span>
            <input
              required
              maxLength={MAX_WORKSPACE_NAME_LENGTH}
              value={form.seasonName}
              onChange={(event) => form.setSeasonName(event.target.value)}
              placeholder="예: 2026 여름 시즌"
            />
          </label>
          <div className="onboarding-date-row">
            <label>
              <span>시작일</span>
              <input
                required
                type="date"
                value={form.startDate}
                onChange={(event) => form.setStartDate(event.target.value)}
              />
            </label>
            <label>
              <span>종료일</span>
              <input
                required
                type="date"
                value={form.endDate}
                min={form.startDate || undefined}
                onChange={(event) => form.setEndDate(event.target.value)}
              />
            </label>
          </div>
          <label>
            <span>구성원 이름</span>
            <textarea
              required
              rows={4}
              value={form.memberNamesInput}
              onChange={(event) => form.setMemberNamesInput(event.target.value)}
              placeholder={'박민서\n김준호\n최유진'}
              aria-describedby="member-names-help"
            />
            <small id="member-names-help">
              줄바꿈 또는 쉼표로 구분해 주세요. 최대 {MAX_INITIAL_MEMBER_COUNT}명, 이름은 각각 {MAX_MEMBER_NAME_LENGTH}자까지 입력할 수 있어요.
            </small>
          </label>
          <label>
            <span>파일럿 생성 코드 <small>(선택)</small></span>
            <input
              type="password"
              autoComplete="off"
              value={form.creationKey}
              onChange={(event) => form.setCreationKey(event.target.value)}
              placeholder="운영자에게 받은 코드"
            />
            <small>워크스페이스 생성 요청에만 사용하며 이 브라우저에 저장하지 않습니다.</small>
          </label>

          {creation.feedbackMessage && (
            <p className="form-error" role="alert">{creation.feedbackMessage}</p>
          )}

          <button
            type="submit"
            className="primary-button onboarding-submit"
            disabled={creation.busy || creation.confirmationReason !== null}
          >
            {creation.submitLabel}
          </button>
        </form>
      </section>
    </main>
  )
}
