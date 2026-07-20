import { useState } from 'react'
import type { FormEvent } from 'react'
import { Icon } from '@/shared/ui/Icon'
import { initialDecisions, initialHandoffItems, initialRoles, initialRoutines, members } from './demoData'
import type { Decision, HandoffItem, Role, Routine, ViewKey } from './types'

const navItems: { key: ViewKey; label: string; icon: Parameters<typeof Icon>[0]['name'] }[] = [
  { key: 'today', label: '오늘', icon: 'today' },
  { key: 'roles', label: '역할', icon: 'roles' },
  { key: 'rhythm', label: '운영', icon: 'rhythm' },
  { key: 'memory', label: '기록', icon: 'memory' },
  { key: 'handoff', label: '바통', icon: 'handoff' },
]

const statusCopy = {
  done: '완료',
  active: '진행 중',
  waiting: '예정',
  late: '지연',
}

function usePersistentState<T>(key: string, initialValue: T) {
  const [value, setValue] = useState<T>(() => {
    try {
      const saved = window.localStorage.getItem(key)
      return saved ? (JSON.parse(saved) as T) : initialValue
    } catch {
      return initialValue
    }
  })

  const updateValue = (next: T | ((current: T) => T)) => {
    setValue((current) => {
      const resolved = typeof next === 'function' ? (next as (current: T) => T)(current) : next
      window.localStorage.setItem(key, JSON.stringify(resolved))
      return resolved
    })
  }

  return [value, updateValue] as const
}

function getMember(personId?: string) {
  return members.find((member) => member.id === personId)
}

type ModalType = 'decision' | 'role' | 'handoffPreview' | null

export default function WorkspaceApp() {
  const [view, setView] = useState<ViewKey>('today')
  const [roles, setRoles] = usePersistentState<Role[]>('baton-roles', initialRoles)
  const [routines, setRoutines] = usePersistentState<Routine[]>('baton-routines', initialRoutines)
  const [decisions, setDecisions] = usePersistentState<Decision[]>('baton-decisions', initialDecisions)
  const [handoffItems, setHandoffItems] = usePersistentState<HandoffItem[]>('baton-handoff', initialHandoffItems)
  const [selectedRoleId, setSelectedRoleId] = useState('curator')
  const [modal, setModal] = useState<ModalType>(null)
  const [inspectorOpen, setInspectorOpen] = useState(false)
  const [toast, setToast] = useState('')

  const selectedRole = roles.find((role) => role.id === selectedRoleId) ?? roles[0]
  const pendingCount = routines.filter((routine) => routine.status !== 'done').length
  const completedCount = routines.filter((routine) => routine.status === 'done').length

  const showToast = (message: string) => {
    setToast(message)
    window.setTimeout(() => setToast(''), 2400)
  }

  const selectRole = (roleId: string) => {
    setSelectedRoleId(roleId)
    setInspectorOpen(true)
  }

  const toggleRoutine = (id: string) => {
    setRoutines((current) =>
      current.map((routine) =>
        routine.id === id
          ? { ...routine, status: routine.status === 'done' ? 'waiting' : 'done' }
          : routine,
      ),
    )
    const routine = routines.find((item) => item.id === id)
    showToast(routine?.status === 'done' ? '완료 표시를 되돌렸어요' : '이번 바통을 넘겼어요')
  }

  const toggleHandoff = (id: string) => {
    setHandoffItems((current) =>
      current.map((item) => (item.id === id ? { ...item, done: !item.done } : item)),
    )
  }

  const handoffProgress = (roleId: string) => {
    const items = handoffItems.filter((item) => item.roleId === roleId)
    if (!items.length) return 0
    return Math.round((items.filter((item) => item.done).length / items.length) * 100)
  }

  const addDecision = (decision: Omit<Decision, 'id' | 'date'>) => {
    const next = {
      ...decision,
      id: `decision-${Date.now()}`,
      date: '방금 전',
    }
    setDecisions((current) => [next, ...current])
    setModal(null)
    showToast('결정과 이유를 팀의 기억에 남겼어요')
  }

  const addRole = (role: Pick<Role, 'name' | 'purpose'>) => {
    const next: Role = {
      ...role,
      id: `role-${Date.now()}`,
      term: '담당 기간 미정',
      progress: 0,
      responsibilities: [],
      routines: [],
      risk: '현재 담당자와 다음 담당자가 모두 비어 있어요.',
    }
    setRoles((current) => [...current, next])
    setSelectedRoleId(next.id)
    setModal(null)
    setView('roles')
    showToast('새 역할을 만들었어요. 이제 담당자를 정해 주세요')
  }

  const resetDemo = () => {
    ;['baton-roles', 'baton-routines', 'baton-decisions', 'baton-handoff'].forEach((key) =>
      window.localStorage.removeItem(key),
    )
    window.location.reload()
  }

  const openView = (key: ViewKey) => {
    setView(key)
    setInspectorOpen(false)
  }

  return (
    <div className="app-shell">
      <Sidebar view={view} onNavigate={openView} onReset={resetDemo} />

      <main className="main-surface">
        <MobileTopbar />
        <div className="page-stage" key={view}>
          {view === 'today' && (
            <TodayView
              roles={roles}
              routines={routines}
              decisions={decisions}
              pendingCount={pendingCount}
              completedCount={completedCount}
              onSelectRole={selectRole}
              onOpenDecision={() => setModal('decision')}
              onToggleRoutine={toggleRoutine}
              onNavigate={openView}
            />
          )}
          {view === 'roles' && (
            <RolesView
              roles={roles}
              selectedRoleId={selectedRoleId}
              onSelectRole={selectRole}
              onAddRole={() => setModal('role')}
              handoffProgress={handoffProgress}
            />
          )}
          {view === 'rhythm' && (
            <RhythmView
              roles={roles}
              routines={routines}
              onSelectRole={selectRole}
              onToggleRoutine={toggleRoutine}
              onOpenRound={() => showToast('7월 23일 회차를 열었어요')}
            />
          )}
          {view === 'memory' && (
            <MemoryView
              decisions={decisions}
              roles={roles}
              onOpenDecision={() => setModal('decision')}
              onSelectRole={selectRole}
            />
          )}
          {view === 'handoff' && (
            <HandoffView
              roles={roles}
              selectedRoleId={selectedRoleId}
              handoffItems={handoffItems}
              onSelectRole={selectRole}
              onToggle={toggleHandoff}
              progress={handoffProgress}
              onPreview={() => setModal('handoffPreview')}
            />
          )}
        </div>
      </main>

      {selectedRole && (
        <RoleInspector
          role={selectedRole}
          decisions={decisions}
          routines={routines}
          progress={handoffProgress(selectedRole.id)}
          open={inspectorOpen}
          onClose={() => setInspectorOpen(false)}
          onOpenHandoff={() => {
            setView('handoff')
            setInspectorOpen(false)
          }}
        />
      )}

      <MobileNav view={view} onNavigate={openView} />

      {modal === 'decision' && (
        <DecisionModal roles={roles} selectedRoleId={selectedRoleId} onClose={() => setModal(null)} onSave={addDecision} />
      )}
      {modal === 'role' && <RoleModal onClose={() => setModal(null)} onSave={addRole} />}
      {modal === 'handoffPreview' && selectedRole && (
        <HandoffPreview
          role={selectedRole}
          decisions={decisions}
          items={handoffItems.filter((item) => item.roleId === selectedRole.id)}
          progress={handoffProgress(selectedRole.id)}
          onClose={() => setModal(null)}
        />
      )}

      {toast && <div className="toast" role="status"><Icon name="check" size={16} />{toast}</div>}
    </div>
  )
}

function Sidebar({
  view,
  onNavigate,
  onReset,
}: {
  view: ViewKey
  onNavigate: (key: ViewKey) => void
  onReset: () => void
}) {
  return (
    <aside className="sidebar">
      <div className="brand"><span className="brand-mark" />BATON</div>

      <div className="workspace-label">현재 팀</div>
      <div className="workspace-switcher">
        <span className="workspace-symbol">알</span>
        <span><strong>알고리즘 한 바퀴</strong><small>2026 여름 시즌</small></span>
      </div>

      <nav className="side-nav" aria-label="주 메뉴">
        {navItems.map((item) => (
          <button
            type="button"
            className={view === item.key ? 'active' : ''}
            key={item.key}
            onClick={() => onNavigate(item.key)}
          >
            <Icon name={item.icon} />
            <span>{item.label}</span>
            {item.key === 'handoff' && <span className="nav-dot" aria-label="확인할 바통 있음" />}
          </button>
        ))}
      </nav>

      <div className="sidebar-bottom">
        <div className="season-mini">
          <div><span>시즌 진행</span><strong>4 / 12주</strong></div>
          <div className="mini-progress"><span style={{ width: '33%' }} /></div>
          <small>9월 17일 종료</small>
        </div>
        <div className="profile-row">
          <span className="avatar avatar-dark">민</span>
          <span><strong>박민서</strong><small>진행 리드</small></span>
          <button type="button" onClick={onReset} title="데모 초기화">초기화</button>
        </div>
      </div>
    </aside>
  )
}

function MobileTopbar() {
  return (
    <header className="mobile-topbar">
      <div className="brand"><span className="brand-mark" />BATON</div>
      <span className="mobile-team">알고리즘 한 바퀴</span>
      <span className="avatar">민</span>
    </header>
  )
}

function MobileNav({ view, onNavigate }: { view: ViewKey; onNavigate: (key: ViewKey) => void }) {
  return (
    <nav className="mobile-nav" aria-label="모바일 주 메뉴">
      {navItems.map((item) => (
        <button type="button" className={view === item.key ? 'active' : ''} key={item.key} onClick={() => onNavigate(item.key)}>
          <Icon name={item.icon} size={20} />
          <span>{item.label}</span>
        </button>
      ))}
    </nav>
  )
}

function PageHeader({
  eyebrow,
  title,
  description,
  action,
}: {
  eyebrow: string
  title: string
  description: string
  action?: React.ReactNode
}) {
  return (
    <header className="page-header">
      <div>
        <span className="eyebrow">{eyebrow}</span>
        <h1>{title}</h1>
        <p>{description}</p>
      </div>
      {action && <div className="page-action">{action}</div>}
    </header>
  )
}

function PrimaryButton({ children, onClick, icon = true }: { children: React.ReactNode; onClick: () => void; icon?: boolean }) {
  return <button type="button" className="primary-button" onClick={onClick}>{icon && <Icon name="plus" size={16} />}{children}</button>
}

function TodayView({
  roles,
  routines,
  decisions,
  pendingCount,
  completedCount,
  onSelectRole,
  onOpenDecision,
  onToggleRoutine,
  onNavigate,
}: {
  roles: Role[]
  routines: Routine[]
  decisions: Decision[]
  pendingCount: number
  completedCount: number
  onSelectRole: (id: string) => void
  onOpenDecision: () => void
  onToggleRoutine: (id: string) => void
  onNavigate: (key: ViewKey) => void
}) {
  return (
    <>
      <PageHeader
        eyebrow="7월 20일 월요일 · 운영 4주 차"
        title={`목요일 모임까지 ${pendingCount}개의 바통이 남았어요`}
        description="이번 회차에서 멈춘 흐름과 다음 담당자를 확인하세요."
        action={<PrimaryButton onClick={onOpenDecision}>결정 남기기</PrimaryButton>}
      />

      <section className="relay-board" aria-labelledby="relay-title">
        <div className="section-heading">
          <div><span className="section-kicker">이번 회차</span><h2 id="relay-title">바통 라인</h2></div>
          <div className="round-meta"><strong>{completedCount}/{routines.length}</strong><span>완료 · 7월 23일 세션</span></div>
        </div>
        <div className="relay-line" role="list">
          {routines.map((routine, index) => {
            const role = roles.find((item) => item.id === routine.ownerRoleId)
            const member = getMember(role?.personId)
            return (
              <button
                type="button"
                className={`relay-step ${routine.status}`}
                key={routine.id}
                onClick={() => role && onSelectRole(role.id)}
                role="listitem"
              >
                <span className="relay-index">0{index + 1}</span>
                <span className="relay-node"><span /></span>
                <span className="relay-status">{statusCopy[routine.status]}</span>
                <strong>{routine.title}</strong>
                <small>{member?.name ?? '담당자 미정'} · {routine.due}</small>
              </button>
            )
          })}
        </div>
      </section>

      <div className="today-lower">
        <section className="plain-section">
          <div className="section-heading compact">
            <div><span className="section-kicker">주의가 필요한 곳</span><h2>멈춘 바통</h2></div>
            <button type="button" className="text-button" onClick={() => onNavigate('roles')}>역할에서 보기 <Icon name="arrow" size={14} /></button>
          </div>
          <div className="signal-list">
            {roles.filter((role) => role.risk).slice(0, 3).map((role, index) => (
              <button type="button" className="signal-row" key={role.id} onClick={() => onSelectRole(role.id)}>
                <span className={`signal-symbol ${index === 0 ? 'urgent' : ''}`}><Icon name="alert" size={15} /></span>
                <span><strong>{role.name}</strong><small>{role.risk}</small></span>
                <Icon name="chevron" size={16} />
              </button>
            ))}
          </div>
        </section>

        <section className="plain-section decision-glimpse">
          <div className="section-heading compact">
            <div><span className="section-kicker">최근 변경</span><h2>결정 기록</h2></div>
            <button type="button" className="text-button" onClick={() => onNavigate('memory')}>전체 기록 <Icon name="arrow" size={14} /></button>
          </div>
          {decisions[0] && (
            <button type="button" className="decision-preview" onClick={() => onNavigate('memory')}>
              <time>{decisions[0].date}</time>
              <blockquote>“{decisions[0].title}”</blockquote>
              <p>{decisions[0].reason}</p>
              <span>{decisions[0].author} 기록</span>
            </button>
          )}
        </section>
      </div>

      <section className="mobile-this-week plain-section">
        <div className="section-heading compact"><div><span className="section-kicker">내가 할 일</span><h2>이번 주 운영</h2></div></div>
        {routines.map((routine) => (
          <RoutineRow key={routine.id} routine={routine} role={roles.find((item) => item.id === routine.ownerRoleId)} onToggle={onToggleRoutine} onSelectRole={onSelectRole} />
        ))}
      </section>
    </>
  )
}

function RolesView({
  roles,
  selectedRoleId,
  onSelectRole,
  onAddRole,
  handoffProgress,
}: {
  roles: Role[]
  selectedRoleId: string
  onSelectRole: (id: string) => void
  onAddRole: () => void
  handoffProgress: (id: string) => number
}) {
  return (
    <>
      <PageHeader
        eyebrow="팀의 책임 지도"
        title="사람이 바뀌어도 역할은 남아요"
        description="현재 담당자와 다음 담당자, 반복되는 책임을 한눈에 확인하세요."
        action={<PrimaryButton onClick={onAddRole}>역할 추가</PrimaryButton>}
      />
      <section className="role-directory">
        <div className="directory-head"><span>역할과 목적</span><span>현재 담당자</span><span>다음 담당자</span><span>바통 준비</span></div>
        {roles.map((role) => {
          const owner = getMember(role.personId)
          const next = getMember(role.nextPersonId)
          return (
            <button type="button" className={`role-row ${selectedRoleId === role.id ? 'selected' : ''}`} key={role.id} onClick={() => onSelectRole(role.id)}>
              <span className="role-main"><span className="role-glyph"><Icon name="roles" size={17} /></span><span><strong>{role.name}</strong><small>{role.purpose}</small></span></span>
              <span className="person-cell">{owner ? <><span className="avatar" style={{ background: owner.tone }}>{owner.initials}</span><span><strong>{owner.name}</strong><small>{role.term}</small></span></> : <em>담당자 미정</em>}</span>
              <span className="next-cell">{next ? <><span className="avatar" style={{ background: next.tone }}>{next.initials}</span>{next.name}</> : <em>아직 미정</em>}</span>
              <span className="progress-cell"><strong>{handoffProgress(role.id)}%</strong><span className="thin-progress"><i style={{ width: `${handoffProgress(role.id)}%` }} /></span><Icon name="chevron" size={16} /></span>
            </button>
          )
        })}
      </section>
      <p className="directory-note"><Icon name="spark" size={15} /> 사람을 먼저 초대하기보다, 팀에 꼭 필요한 책임부터 역할로 정리해 보세요.</p>
    </>
  )
}

function RhythmView({
  roles,
  routines,
  onSelectRole,
  onToggleRoutine,
  onOpenRound,
}: {
  roles: Role[]
  routines: Routine[]
  onSelectRole: (id: string) => void
  onToggleRoutine: (id: string) => void
  onOpenRound: () => void
}) {
  const phases: Routine['phase'][] = ['모임 전', '모임 중', '모임 후']
  return (
    <>
      <PageHeader
        eyebrow="매주 반복되는 리듬"
        title="우리 팀은 이렇게 움직여요"
        description="매번 설명하던 일을 루틴으로 만들고, 완료되면 다음 역할로 넘깁니다."
        action={<PrimaryButton onClick={onOpenRound} icon={false}>이번 회차 열기</PrimaryButton>}
      />
      <div className="rhythm-timeline">
        {phases.map((phase, phaseIndex) => (
          <section className="rhythm-phase" key={phase}>
            <div className="phase-marker"><span>0{phaseIndex + 1}</span><h2>{phase}</h2></div>
            <div className="phase-content">
              {routines.filter((routine) => routine.phase === phase).map((routine) => (
                <RoutineRow key={routine.id} routine={routine} role={roles.find((role) => role.id === routine.ownerRoleId)} onToggle={onToggleRoutine} onSelectRole={onSelectRole} />
              ))}
            </div>
          </section>
        ))}
      </div>
      <button type="button" className="add-routine-line" onClick={() => onOpenRound()}><Icon name="plus" size={15} /> 다음 회차에 반복할 일 추가하기</button>
    </>
  )
}

function RoutineRow({
  routine,
  role,
  onToggle,
  onSelectRole,
}: {
  routine: Routine
  role?: Role
  onToggle: (id: string) => void
  onSelectRole: (id: string) => void
}) {
  const member = getMember(role?.personId)
  return (
    <div className={`routine-row ${routine.status}`}>
      <button type="button" className="check-button" onClick={() => onToggle(routine.id)} aria-label={`${routine.title} ${routine.status === 'done' ? '완료 취소' : '완료 처리'}`}>
        {routine.status === 'done' && <Icon name="check" size={14} />}
      </button>
      <button type="button" className="routine-copy" onClick={() => role && onSelectRole(role.id)}>
        <span><strong>{routine.title}</strong><small>{routine.detail}</small></span>
        <time>{routine.due}</time>
      </button>
      <button type="button" className="routine-owner" onClick={() => role && onSelectRole(role.id)}>
        {member && <span className="avatar" style={{ background: member.tone }}>{member.initials}</span>}
        <span><strong>{role?.name}</strong><small>{member?.name ?? '담당자 미정'}</small></span>
      </button>
    </div>
  )
}

function MemoryView({
  decisions,
  roles,
  onOpenDecision,
  onSelectRole,
}: {
  decisions: Decision[]
  roles: Role[]
  onOpenDecision: () => void
  onSelectRole: (id: string) => void
}) {
  return (
    <>
      <PageHeader
        eyebrow="팀의 결정 원장"
        title="결과뿐 아니라 이유도 남겨두세요"
        description="채팅에서 사라질 결정을 다음 시즌도 이해할 수 있는 기록으로 바꿉니다."
        action={<PrimaryButton onClick={onOpenDecision}>결정 남기기</PrimaryButton>}
      />
      <section className="memory-ledger">
        <div className="memory-rule"><span>최근 결정</span><span>{decisions.length}개의 기록</span></div>
        {decisions.map((decision, index) => (
          <article className="decision-entry" key={decision.id}>
            <div className="decision-number">{String(decisions.length - index).padStart(2, '0')}</div>
            <div className="decision-body">
              <div className="decision-meta"><time>{decision.date}</time><span>{decision.author}</span></div>
              <h2>{decision.title}</h2>
              <div className="decision-reason"><span>이유</span><p>{decision.reason}</p></div>
              <div className="decision-alternative"><span>검토한 다른 선택</span><p>{decision.alternative}</p></div>
              <div className="decision-tags">
                {decision.roleIds.map((roleId) => {
                  const role = roles.find((item) => item.id === roleId)
                  return role ? <button type="button" key={roleId} onClick={() => onSelectRole(roleId)}>{role.name}</button> : null
                })}
              </div>
            </div>
          </article>
        ))}
      </section>
    </>
  )
}

function HandoffView({
  roles,
  selectedRoleId,
  handoffItems,
  onSelectRole,
  onToggle,
  progress,
  onPreview,
}: {
  roles: Role[]
  selectedRoleId: string
  handoffItems: HandoffItem[]
  onSelectRole: (id: string) => void
  onToggle: (id: string) => void
  progress: (id: string) => number
  onPreview: () => void
}) {
  const selected = roles.find((role) => role.id === selectedRoleId) ?? roles[0]

  if (!selected) {
    return (
      <>
        <PageHeader
          eyebrow="역할 인수인계"
          title="첫 역할부터 만들어 주세요"
          description="역할이 생기면 책임과 운영 맥락을 바통북으로 정리할 수 있습니다."
        />
        <div className="empty-state">
          <Icon name="handoff" size={28} />
          <strong>넘겨줄 역할이 아직 없어요</strong>
          <p>역할 화면에서 팀의 첫 역할을 추가해 주세요.</p>
        </div>
      </>
    )
  }

  const items = handoffItems.filter((item) => item.roleId === selected.id)
  const next = getMember(selected.nextPersonId)
  return (
    <>
      <PageHeader
        eyebrow="시즌 종료까지 59일"
        title="다음 사람이 헤매지 않도록"
        description="역할의 책임과 맥락을 바통북으로 정리해 다음 담당자에게 넘깁니다."
        action={<PrimaryButton onClick={onPreview} icon={false}>바통북 미리보기</PrimaryButton>}
      />
      <div className="handoff-role-tabs" role="tablist" aria-label="역할별 바통">
        {roles.map((role) => (
          <button type="button" role="tab" aria-selected={selected.id === role.id} className={selected.id === role.id ? 'active' : ''} key={role.id} onClick={() => onSelectRole(role.id)}>
            <span>{role.name}</span><strong>{progress(role.id)}%</strong>
          </button>
        ))}
      </div>
      <section className="handoff-workspace">
        <div className="handoff-summary">
          <span className="section-kicker">{selected.name}</span>
          <h2>{next ? `${next.name}님에게 넘길 바통` : '다음 담당자를 기다리는 바통'}</h2>
          <p>{selected.purpose}</p>
          <div className="handoff-score"><strong>{progress(selected.id)}%</strong><span><i style={{ width: `${progress(selected.id)}%` }} /></span><small>{items.filter((item) => item.done).length}/{items.length || 0} 항목 준비됨</small></div>
        </div>
        <div className="handoff-checklist">
          {items.length ? items.map((item) => (
            <label className={item.done ? 'done' : ''} key={item.id}>
              <input type="checkbox" checked={item.done} onChange={() => onToggle(item.id)} />
              <span className="custom-check">{item.done && <Icon name="check" size={14} />}</span>
              <span><strong>{item.label}</strong><small>{item.category}</small></span>
            </label>
          )) : (
            <div className="empty-state"><Icon name="handoff" size={28} /><strong>아직 바통북 항목이 없어요</strong><p>역할의 책임과 반복 루틴을 먼저 추가해 주세요.</p></div>
          )}
        </div>
      </section>
    </>
  )
}

function RoleInspector({
  role,
  decisions,
  routines,
  progress,
  open,
  onClose,
  onOpenHandoff,
}: {
  role: Role
  decisions: Decision[]
  routines: Routine[]
  progress: number
  open: boolean
  onClose: () => void
  onOpenHandoff: () => void
}) {
  const owner = getMember(role.personId)
  const next = getMember(role.nextPersonId)
  const relatedRoutine = routines.find((routine) => routine.ownerRoleId === role.id && routine.status !== 'done')
  const relatedDecision = decisions.find((decision) => decision.roleIds.includes(role.id))
  return (
    <aside className={`inspector ${open ? 'is-open' : ''}`} aria-label="선택한 역할 상세">
      <button type="button" className="inspector-close" onClick={onClose} aria-label="상세 닫기"><Icon name="close" /></button>
      <div className="inspector-topline"><span>선택한 역할</span><span className="live-dot">운영 중</span></div>
      <h2>{role.name}</h2>
      <p className="inspector-purpose">{role.purpose}</p>

      <div className="owner-block">
        <span className="block-label">현재 담당자</span>
        {owner ? <div><span className="avatar avatar-large" style={{ background: owner.tone }}>{owner.initials}</span><span><strong>{owner.name}</strong><small>{role.term}</small></span></div> : <button type="button" className="assign-button">담당자 정하기 <Icon name="arrow" size={14} /></button>}
      </div>

      {role.risk && <div className="risk-note"><Icon name="alert" size={17} /><span><strong>기억이 끊길 수 있어요</strong>{role.risk}</span></div>}

      <div className="inspector-section">
        <span className="block-label">핵심 책임</span>
        <ul>{role.responsibilities.length ? role.responsibilities.map((item) => <li key={item}><Icon name="check" size={13} />{item}</li>) : <li className="muted">아직 정리된 책임이 없어요.</li>}</ul>
      </div>

      {relatedRoutine && <div className="inspector-section next-event"><span className="block-label">다음 루틴</span><strong>{relatedRoutine.title}</strong><small>{relatedRoutine.due} · {relatedRoutine.detail}</small></div>}
      {relatedDecision && <div className="inspector-section linked-decision"><span className="block-label">연결된 결정</span><p>“{relatedDecision.title}”</p><small>{relatedDecision.date}</small></div>}

      <div className="inspector-handoff">
        <div><span className="block-label">바통 준비도</span><strong>{progress}%</strong></div>
        <div className="thin-progress"><i style={{ width: `${progress}%` }} /></div>
        <p>{next ? `다음 담당자 · ${next.name}` : '다음 담당자가 아직 정해지지 않았어요.'}</p>
        <button type="button" onClick={onOpenHandoff}>바통 정리하기 <Icon name="arrow" size={15} /></button>
      </div>
    </aside>
  )
}

function ModalShell({ title, description, onClose, children }: { title: string; description: string; onClose: () => void; children: React.ReactNode }) {
  return (
    <div className="modal-backdrop" role="presentation" onMouseDown={(event) => event.currentTarget === event.target && onClose()}>
      <section className="modal" role="dialog" aria-modal="true" aria-labelledby="modal-title">
        <button type="button" className="modal-close" onClick={onClose} aria-label="닫기"><Icon name="close" /></button>
        <span className="section-kicker">BATON</span>
        <h2 id="modal-title">{title}</h2>
        <p className="modal-description">{description}</p>
        {children}
      </section>
    </div>
  )
}

function DecisionModal({ roles, selectedRoleId, onClose, onSave }: { roles: Role[]; selectedRoleId: string; onClose: () => void; onSave: (decision: Omit<Decision, 'id' | 'date'>) => void }) {
  const [title, setTitle] = useState('')
  const [reason, setReason] = useState('')
  const [alternative, setAlternative] = useState('')
  const [roleId, setRoleId] = useState(selectedRoleId)
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!title.trim() || !reason.trim()) return
    onSave({ title: title.trim(), reason: reason.trim(), alternative: alternative.trim() || '별도 대안을 검토하지 않음', author: '박민서', roleIds: [roleId] })
  }
  return (
    <ModalShell title="결정과 이유 남기기" description="나중에 ‘왜 이렇게 했지?’라는 질문에 답할 수 있도록 맥락을 함께 적어주세요." onClose={onClose}>
      <form className="modal-form" onSubmit={submit}>
        <label><span>무엇을 바꾸기로 했나요?</span><input autoFocus required value={title} onChange={(event) => setTitle(event.target.value)} placeholder="예: 세션 시작 시간을 30분 앞당긴다" /></label>
        <label><span>왜 이 선택을 했나요?</span><textarea required value={reason} onChange={(event) => setReason(event.target.value)} placeholder="반복된 문제나 관찰한 근거를 적어주세요" rows={3} /></label>
        <label><span>검토한 다른 선택</span><input value={alternative} onChange={(event) => setAlternative(event.target.value)} placeholder="예: 세션 시간을 30분 연장하기" /></label>
        <label><span>영향받는 역할</span><select value={roleId} onChange={(event) => setRoleId(event.target.value)}>{roles.map((role) => <option key={role.id} value={role.id}>{role.name}</option>)}</select></label>
        <div className="form-actions"><button type="button" className="secondary-button" onClick={onClose}>취소</button><button type="submit" className="primary-button">결정 기록하기</button></div>
      </form>
    </ModalShell>
  )
}

function RoleModal({ onClose, onSave }: { onClose: () => void; onSave: (role: Pick<Role, 'name' | 'purpose'>) => void }) {
  const [name, setName] = useState('')
  const [purpose, setPurpose] = useState('')
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!name.trim() || !purpose.trim()) return
    onSave({ name: name.trim(), purpose: purpose.trim() })
  }
  return (
    <ModalShell title="새 역할 만들기" description="사람의 직함보다, 팀에 계속 남아야 할 책임을 이름으로 붙여주세요." onClose={onClose}>
      <form className="modal-form" onSubmit={submit}>
        <label><span>역할 이름</span><input autoFocus required value={name} onChange={(event) => setName(event.target.value)} placeholder="예: 질문 큐레이터" /></label>
        <label><span>이 역할이 존재하는 이유</span><textarea required value={purpose} onChange={(event) => setPurpose(event.target.value)} placeholder="이 역할이 팀에서 해결하는 문제를 적어주세요" rows={3} /></label>
        <div className="form-actions"><button type="button" className="secondary-button" onClick={onClose}>취소</button><button type="submit" className="primary-button">역할 만들기</button></div>
      </form>
    </ModalShell>
  )
}

function HandoffPreview({ role, decisions, items, progress, onClose }: { role: Role; decisions: Decision[]; items: HandoffItem[]; progress: number; onClose: () => void }) {
  const owner = getMember(role.personId)
  const next = getMember(role.nextPersonId)
  const relatedDecisions = decisions.filter((decision) => decision.roleIds.includes(role.id))
  return (
    <ModalShell title={`${role.name} 바통북`} description={`${owner?.name ?? '이전 담당자'}에서 ${next?.name ?? '다음 담당자'}에게 이어질 역할 기록입니다.`} onClose={onClose}>
      <div className="book-preview">
        <div className="book-progress"><span>준비도</span><strong>{progress}%</strong></div>
        <section><span>01 · 역할의 목적</span><p>{role.purpose}</p></section>
        <section><span>02 · 반복하는 일</span><ul>{role.routines.map((item) => <li key={item}>{item}</li>)}</ul></section>
        <section><span>03 · 중요한 결정</span>{relatedDecisions.length ? relatedDecisions.map((item) => <blockquote key={item.id}>“{item.title}”<small>{item.reason}</small></blockquote>) : <p>연결된 결정이 아직 없습니다.</p>}</section>
        <section><span>04 · 남은 정리</span><ul>{items.filter((item) => !item.done).map((item) => <li key={item.id}>{item.label}</li>)}</ul></section>
        <button type="button" className="primary-button full-button" onClick={onClose}>미리보기 닫기</button>
      </div>
    </ModalShell>
  )
}
