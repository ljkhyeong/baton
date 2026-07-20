import { useState } from 'react'
import type { FormEvent } from 'react'
import { useMutation } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { createWorkspace, saveAccessKey } from '@/features/workspace/api'
import type { CreateWorkspaceRequest } from '@/features/workspace/types'

function splitMemberNames(value: string) {
  return [...new Set(value.split(/[\n,]/).map((name) => name.trim()).filter(Boolean))]
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '작업 공간을 만들지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

export default function OnboardingForm() {
  const navigate = useNavigate()
  const [teamName, setTeamName] = useState('')
  const [seasonName, setSeasonName] = useState('')
  const [startDate, setStartDate] = useState('')
  const [endDate, setEndDate] = useState('')
  const [memberNamesInput, setMemberNamesInput] = useState('')
  const [validationMessage, setValidationMessage] = useState('')

  const createMutation = useMutation({
    mutationFn: (request: CreateWorkspaceRequest) => createWorkspace(request),
    onSuccess: ({ teamId, seasonId, accessKey }) => {
      const workspacePath = `/teams/${encodeURIComponent(teamId)}/seasons/${encodeURIComponent(seasonId)}`
      const saved = saveAccessKey(teamId, accessKey)
      navigate(saved ? workspacePath : `${workspacePath}#accessKey=${encodeURIComponent(accessKey)}`)
    },
  })

  const submit = (event: FormEvent) => {
    event.preventDefault()
    setValidationMessage('')

    const memberNames = splitMemberNames(memberNamesInput)
    if (!memberNames.length) {
      setValidationMessage('함께할 구성원을 한 명 이상 입력해 주세요.')
      return
    }
    if (endDate < startDate) {
      setValidationMessage('종료일은 시작일보다 빠를 수 없습니다.')
      return
    }

    createMutation.mutate({
      teamName: teamName.trim(),
      seasonName: seasonName.trim(),
      startDate,
      endDate,
      memberNames,
    })
  }

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
        <div className="onboarding-form-heading">
          <span className="section-kicker">새 작업 공간</span>
          <h2 id="workspace-form-title">우리 스터디를 시작해요</h2>
          <p>지금 입력한 정보로 첫 시즌과 공유 작업 공간을 만듭니다.</p>
        </div>

        <form className="onboarding-form" onSubmit={submit}>
          <label>
            <span>팀 이름</span>
            <input required autoFocus value={teamName} onChange={(event) => setTeamName(event.target.value)} placeholder="예: 알고리즘 한 바퀴" />
          </label>
          <label>
            <span>시즌 이름</span>
            <input required value={seasonName} onChange={(event) => setSeasonName(event.target.value)} placeholder="예: 2026 여름 시즌" />
          </label>
          <div className="onboarding-date-row">
            <label><span>시작일</span><input required type="date" value={startDate} onChange={(event) => setStartDate(event.target.value)} /></label>
            <label><span>종료일</span><input required type="date" value={endDate} min={startDate || undefined} onChange={(event) => setEndDate(event.target.value)} /></label>
          </div>
          <label>
            <span>구성원 이름</span>
            <textarea required rows={4} value={memberNamesInput} onChange={(event) => setMemberNamesInput(event.target.value)} placeholder={'박민서\n김준호\n최유진'} />
            <small>줄바꿈 또는 쉼표로 구분해 주세요.</small>
          </label>

          {(validationMessage || createMutation.error) && (
            <p className="form-error" role="alert">{validationMessage || errorMessage(createMutation.error)}</p>
          )}

          <button type="submit" className="primary-button onboarding-submit" disabled={createMutation.isPending}>
            {createMutation.isPending ? '작업 공간 만드는 중…' : '작업 공간 만들기'}
          </button>
        </form>
      </section>
    </main>
  )
}
