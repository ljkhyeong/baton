import { useContext, useState } from 'react'
import { createPortal } from 'react-dom'
import { useWorkspacePrint } from '@/features/workspace/useWorkspacePrint'
import { BriefSourceContext } from './BriefSources'
import { attentionReasons, editionSections } from './types'
import type { AttentionItem, BriefEdition, BriefScope } from './types'

export function BriefEditionActions({ edition, workspaceName, scope, loading }: {
  edition: BriefEdition; workspaceName: string; scope: BriefScope; loading: boolean
}) {
  const printEdition = useWorkspacePrint(edition.editionId)
  const { sources, loading: sourcesLoading } = useContext(BriefSourceContext)
  const [copied, setCopied] = useState(false)
  const [manualLink, setManualLink] = useState('')
  const [copying, setCopying] = useState(false)
  const time = new Intl.DateTimeFormat('ko-KR', { timeZone: edition.zoneId, dateStyle: 'medium', timeStyle: 'short' })
  const copyLink = async () => {
    const link = new URL(`/teams/${scope.teamId}/seasons/${scope.seasonId}`, window.location.origin)
    link.searchParams.set('brief', edition.editionId)
    setCopied(false); setManualLink(''); setCopying(true)
    try {
      await navigator.clipboard.writeText(link.href)
      setCopied(true)
    } catch {
      setManualLink(link.href)
    } finally { setCopying(false) }
  }
  return <>
    <div className="brief-pagination" aria-label="선택한 주간 요약 공유와 출력">
      <button type="button" disabled={loading || copying} onClick={() => void copyLink()}>이 주간 요약 링크 복사</button>
      <button type="button" disabled={loading || sourcesLoading} onClick={printEdition}>이 주간 요약 인쇄·PDF 저장</button>
    </div>
    <p className="brief-note">링크를 열려면 로그인과 해당 팀의 접근 권한이 필요합니다. PDF는 인쇄 창의 저장 옵션에서 선택할 수 있습니다.</p>
    {copied && <p role="status">선택한 주간 요약 링크를 복사했습니다.</p>}
    {manualLink && <label className="brief-copy-fallback">자동 복사를 사용할 수 없습니다. 아래 링크를 직접 복사해 주세요.
      <input aria-label="직접 복사할 주간 요약 링크" value={manualLink} readOnly onFocus={(event) => event.currentTarget.select()} />
    </label>}
    {!loading && createPortal(<article className="brief-print-sheet" aria-label="인쇄할 주간 요약">
      <header>
        <p className="brief-print-brand">BATON BRIEF</p>
        <h1>{edition.weekStart} 시작 주 요약</h1>
        <p>{workspaceName}</p>
        <dl>
          <div><dt>생성 번호</dt><dd>{edition.generation}</dd></div>
          <div><dt>생성 시각</dt><dd>{time.format(new Date(edition.generatedAt))} ({edition.zoneId})</dd></div>
          <div><dt>집계 구간</dt><dd>{time.format(new Date(edition.windowStart))} 이상 ~ {time.format(new Date(edition.windowEnd))} 미만</dd></div>
          <div><dt>선정 항목</dt><dd>{edition.items.length}건</dd></div>
        </dl>
      </header>
      <p>항목 상태는 주간 요약 생성 당시 기준입니다. 업무명은 현재 이름입니다.</p>
      {edition.items.length === 0 && <p>이 주간 요약에 선정된 점검 항목이 없습니다.</p>}
      {editionSections.map((section) => {
        const items = edition.items.filter((item) => item.section === section.value)
        return items.length > 0 && <section key={section.value ?? 'legacy'}>
          <h2>{section.label} · {items.length}건</h2>
          <ol>{items.map((item) => {
            const source = sources.find((entry) => entry.eventType === item.reasonCode && entry.sourceReference === item.sourceReference)
            return <li key={`${item.reasonCode}:${item.sourceReference}`}>
              <h3>{attentionReasons[item.reasonCode as AttentionItem['reasonCode']]}</h3>
              <p>현재 업무명: {source?.target?.title ?? '확인할 수 없음'}</p>
              <p>{item.severity === 'HIGH' ? '높음' : '보통'} · {item.status === 'ACTIVE' ? '미해결' : '해결'} · 상태 기록 {time.format(new Date(item.observedAt))}</p>
              <p className="brief-print-evidence">원본 항목 ID: {item.sourceReference}<br />
                {item.aggregateRevision === null ? '이전 주간 요약: 원본 버전·누락 이력 미기록'
                  : `원본 버전 ${item.aggregateRevision} · ${item.revisionGap ? '누락 이력 있음' : '누락 이력 없음'}`}</p>
            </li>
          })}</ol>
        </section>
      })}
      <footer>주간 요약 식별자: {edition.editionId}<br />수신 커서 {edition.sourceCursor} · 선정 규칙 {edition.ruleVersion}</footer>
    </article>, document.body)}
  </>
}
