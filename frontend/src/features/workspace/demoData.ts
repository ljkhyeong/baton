import type { Decision, HandoffItem, Member, Role, Routine } from './types'

export const members: Member[] = [
  { id: 'minseo', name: '박민서', initials: '민', tone: '#d9e4da' },
  { id: 'junho', name: '김준호', initials: '준', tone: '#f1d6cc' },
  { id: 'yujin', name: '최유진', initials: '유', tone: '#d8dfee' },
  { id: 'sora', name: '이소라', initials: '소', tone: '#eee3bf' },
]

export const initialRoles: Role[] = [
  {
    id: 'facilitator',
    name: '진행 리드',
    purpose: '모임의 흐름을 설계하고 모두가 정해진 시간 안에 참여하도록 돕습니다.',
    personId: 'minseo',
    term: '7월 2일 — 9월 17일',
    progress: 82,
    responsibilities: ['주간 세션 진행', '시간 배분', '회고 질문 준비'],
    routines: ['목요일 세션 진행', '세션 종료 전 회고'],
    risk: '온라인 미팅 호스트 권한이 개인 계정에 연결되어 있어요.',
  },
  {
    id: 'curator',
    name: '문제 큐레이터',
    purpose: '이번 주 학습 목표에 맞는 문제를 난이도별로 선정합니다.',
    personId: 'junho',
    nextPersonId: 'sora',
    term: '7월 2일 — 8월 13일',
    progress: 63,
    responsibilities: ['문제 5개 선정', '난이도 균형 확인', '문제 링크 공유'],
    routines: ['월요일 문제 선정', '수요일 힌트 공개'],
    risk: '문제 선정 기준이 준호의 개인 메모에만 있어요.',
  },
  {
    id: 'archivist',
    name: '기록지기',
    purpose: '풀이와 결정의 맥락을 다음 회차와 다음 시즌에 남깁니다.',
    personId: 'yujin',
    term: '7월 2일 — 9월 17일',
    progress: 48,
    responsibilities: ['풀이노트 게시', '결정 기록 정리', '자료 링크 점검'],
    routines: ['금요일 풀이노트 게시', '월말 기록 정리'],
    risk: '지난 3회차 중 2회는 풀이노트가 늦게 게시됐어요.',
  },
  {
    id: 'attendance',
    name: '출석지기',
    purpose: '참석 여부와 보충 일정을 확인해 학습 흐름이 끊기지 않게 합니다.',
    personId: 'sora',
    nextPersonId: 'junho',
    term: '7월 2일 — 8월 13일',
    progress: 76,
    responsibilities: ['참석 확인', '리마인드 발송', '결석자 보충 안내'],
    routines: ['수요일 참석 확인', '금요일 보충 안내'],
  },
]

export const initialRoutines: Routine[] = [
  {
    id: 'pick-problems',
    title: '문제 5개 선정',
    phase: '모임 전',
    due: '오늘 20:00',
    ownerRoleId: 'curator',
    status: 'active',
    detail: '그래프 2개 · DP 2개 · 구현 1개',
  },
  {
    id: 'attendance-check',
    title: '참석 여부 답하기',
    phase: '모임 전',
    due: '수요일 18:00',
    ownerRoleId: 'attendance',
    status: 'done',
    detail: '4명 모두 응답 완료',
  },
  {
    id: 'host-session',
    title: '목요일 세션 진행',
    phase: '모임 중',
    due: '목요일 20:30',
    ownerRoleId: 'facilitator',
    status: 'waiting',
    detail: '온라인 · 90분',
  },
  {
    id: 'publish-notes',
    title: '7월 16일 풀이노트 게시',
    phase: '모임 후',
    due: '2일 지연',
    ownerRoleId: 'archivist',
    status: 'late',
    detail: '세션 핵심 풀이 3개가 아직 정리되지 않았어요.',
  },
]

export const initialDecisions: Decision[] = [
  {
    id: 'decision-1',
    title: '결석자는 금요일까지 대체 풀이 2개를 제출한다',
    reason: '결석 후 다음 주 난이도를 따라가기 어렵다는 회고가 반복됐습니다.',
    alternative: '녹화 영상 시청만 요구하기',
    date: '7월 10일',
    author: '박민서',
    roleIds: ['attendance', 'archivist'],
  },
  {
    id: 'decision-2',
    title: '한 회차의 문제 수를 7개에서 5개로 줄인다',
    reason: '문제 수보다 풀이를 설명하고 비교하는 시간이 더 중요하다고 판단했습니다.',
    alternative: '모임 시간을 30분 늘리기',
    date: '7월 3일',
    author: '김준호',
    roleIds: ['curator', 'facilitator'],
  },
]

export const initialHandoffItems: HandoffItem[] = [
  { id: 'h1', roleId: 'curator', label: '역할의 한 줄 목적', category: '책임', done: true },
  { id: 'h2', roleId: 'curator', label: '매주 반복하는 일', category: '루틴', done: true },
  { id: 'h3', roleId: 'curator', label: '문제 선정 난이도 기준', category: '자료', done: false },
  { id: 'h4', roleId: 'curator', label: '자주 생기는 문제와 대응법', category: '조언', done: false },
  { id: 'h5', roleId: 'curator', label: '다음 담당자의 첫 행동', category: '조언', done: true },
  { id: 'h6', roleId: 'facilitator', label: '온라인 미팅 계정 이전', category: '자료', done: false },
  { id: 'h7', roleId: 'facilitator', label: '세션 진행 순서', category: '루틴', done: true },
  { id: 'h8', roleId: 'archivist', label: '풀이노트 작성 기준', category: '자료', done: false },
  { id: 'h9', roleId: 'archivist', label: '기록 보관 위치', category: '자료', done: true },
  { id: 'h10', roleId: 'attendance', label: '결석자 보충 안내 문구', category: '루틴', done: true },
]
