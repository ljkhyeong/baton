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
          <span className="section-kicker">시작하기</span>
          <h1 id="onboarding-title">담당 업무부터<br />인수인계까지.</h1>
          <p>담당 업무, 결정 이유, 인수인계 자료를 한곳에서 관리하세요.</p>
        </div>
        <ol className="onboarding-points">
          <li><span>01</span><strong>활동 기간 정하기</strong><small>팀이 함께 활동할 기간을 ‘시즌’으로 관리합니다.</small></li>
          <li><span>02</span><strong>담당자 정하기</strong><small>누가 어떤 업무를 맡을지 정합니다.</small></li>
          <li><span>03</span><strong>인수인계 준비하기</strong><small>담당 업무와 참고 자료를 남깁니다.</small></li>
        </ol>
      </section>

      <section className="onboarding-form-panel" aria-labelledby="workspace-form-title">
        <div className="onboarding-form-topline">
          <div className="onboarding-form-heading">
            <span className="section-kicker">새 작업 공간</span>
            <h2 id="workspace-form-title">팀 작업 공간 만들기</h2>
            <p>팀 이름, 활동 기간, 구성원을 입력하세요.</p>
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
                    임시 기록 삭제 재시도
                  </button>
                )
              : (
                  <button
                    type="button"
                    className="text-button"
                    onClick={creation.startNewRequest}
                  >
                    기존 작업 공간 확인 후 새로 만들기
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
                      aria-label={`${workspace.teamName} ${workspace.seasonName} 이 기기에서 공유 링크 삭제`}
                    >
                      이 기기에서 삭제
                    </button>
                  </li>
                )
              })}
            </ul>
          </section>
        )}

        <form className="onboarding-form" onSubmit={form.submit}>
          <label><span>템플릿 선택</span><select value={form.template ?? ''} disabled={creation.busy}
            onChange={event => form.setTemplate((event.target.value || undefined) as CreateWorkspaceRequest['template'])}>
            <option value="">템플릿 없이 시작</option>
            {Object.entries(workspaceTemplates).map(([id, template]) => <option key={id} value={id}>{template.name}</option>)}
          </select></label>
          {form.template && <section className="workspace-template-preview" aria-label="템플릿 미리보기">
            <h3>{workspaceTemplates[form.template].name}</h3>
            <p>역할: {workspaceTemplates[form.template].roles.join(' · ')}</p>
            <p>반복 업무: {workspaceTemplates[form.template].routines.join(' · ')}</p>
            <small>역할 3개와 반복 업무 3개를 함께 만듭니다. 담당자·마감·반복 일정은 만든 뒤 정해 주세요. 생성한 내용은 수정할 수 있습니다.</small>
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
            <span>작업 공간 생성 코드 <small>(선택)</small></span>
            <input
              type="password"
              autoComplete="off"
              value={form.creationKey}
              onChange={(event) => form.setCreationKey(event.target.value)}
              placeholder="운영자에게 받은 코드"
            />
            <small>작업 공간을 만들 때만 사용하며 브라우저에 저장하지 않습니다.</small>
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
