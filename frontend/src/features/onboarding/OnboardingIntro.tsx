import { Link } from 'react-router-dom'
import { Icon } from '@/shared/ui/Icon'

const flowSteps = [
  { title: '시즌과 역할', description: '함께 활동할 기간을 ‘시즌’으로 만들고 역할마다 담당자와 담당 기간을 정합니다.' },
  { title: '반복 업무와 회차', description: '주간·격주 반복 업무를 정하면 회차가 만들어지고, 할 일 화면에서 완료를 체크합니다.' },
  { title: '결정과 자료', description: '결정 내용과 이유, 검토한 대안을 기록에 남기고 자료 링크 상태를 점검합니다.' },
  { title: '인수인계', description: '체크리스트와 문서를 준비해 전달합니다. 다음 담당자가 수락하면 담당이 바뀝니다.' },
]

function BatonWordmark() {
  return (
    <svg className="landing-wordmark" viewBox="0 0 608 220" aria-hidden="true" focusable="false">
      <g fill="currentColor" fillRule="evenodd">
        <path d="M0 0H70C96 0 110 16 110 46V66C110 86 102 98 88 104C104 110 112 124 112 148V172C112 202 96 220 68 220H0ZM36 34H64C72 34 76 39 76 48V66C76 79 71 86 62 86H36ZM36 120H66C74 120 78 126 78 136V170C78 180 73 186 64 186H36Z" />
        <path transform="translate(124 0)" d="M0 220L36 0H76L112 220H80L73 178H39L32 220ZM44 146H68L56 73Z" />
        <path transform="translate(248 0)" d="M0 0H112V36H74V220H38V36H0Z" />
        <path transform="translate(372 0)" d="M0 58C0 22 22 0 56 0C90 0 112 22 112 58V162C112 198 90 220 56 220C22 220 0 198 0 162ZM36 62C36 44 44 34 56 34C68 34 76 44 76 62V158C76 176 68 186 56 186C44 186 36 176 36 158Z" />
        <path transform="translate(496 0)" d="M0 220V0H36L76 110V0H112V220H76L36 110V220Z" />
      </g>
    </svg>
  )
}

export default function OnboardingIntro() {
  return (
    <>
      <section className="landing-hero" aria-labelledby="onboarding-title">
        <span className="landing-hero-baton" aria-hidden="true" />
        <div className="landing-hero-inner">
          <header className="landing-hero-bar">
            <div className="brand landing-brand"><span className="brand-mark" />BATON</div>
            <nav aria-label="계정">
              <Link to="/my-teams">내 팀</Link>
              <Link to="/login">계정 로그인</Link>
            </nav>
          </header>
          <p className="landing-kicker" aria-hidden="true"><span>Team roles &amp; handoff</span><span>Season · Role · Handoff</span></p>
          <BatonWordmark />
          <div className="landing-hero-copy">
            <h1 id="onboarding-title">담당 업무부터 <br />인수인계까지.</h1>
            <div>
              <p>담당자가 바뀌어도 업무와 결정 기록이 이어집니다. 담당 업무, 결정 이유, 인수인계 자료를 한곳에서 관리하세요.</p>
              <a className="landing-cta" href="#start">팀 작업 공간 만들기<Icon name="arrow" /></a>
            </div>
          </div>
        </div>
      </section>

      <section className="landing-section" aria-labelledby="landing-flow-title">
        <p className="landing-eyebrow">사용 흐름</p>
        <h2 id="landing-flow-title">한 시즌을 네 구간으로 이어 달립니다.</h2>
        <ol className="landing-steps">
          {flowSteps.map((step) => (
            <li key={step.title}>
              <h3>{step.title}</h3>
              <p>{step.description}</p>
            </li>
          ))}
        </ol>
      </section>

      <section className="landing-section" aria-labelledby="landing-screens-title">
        <p className="landing-eyebrow">화면</p>
        <h2 id="landing-screens-title">할 일, 넘겨줄 것, 남길 결정.</h2>
        <div className="landing-screens">
          <figure className="landing-screen">
            <figcaption><strong>할 일</strong><span>이번 회차에 남은 업무와 넘어가는 바통을 먼저 보여 줍니다.</span></figcaption>
            <div className="landing-shot" aria-hidden="true">
              <small>8월 24일 월요일</small>
              <b className="landing-shot-title">남은 업무 1개</b>
              <span className="landing-shot-progress"><i /></span>
              <ul>
                <li><span className="landing-shot-check" />풀이 노트 정리<em className="is-progress">진행</em></li>
                <li className="is-done"><span className="landing-shot-check"><Icon name="check" size={12} /></span>문제 5개 선정<em>완료</em></li>
              </ul>
              <div className="landing-shot-baton"><strong>기록 담당 바통이 넘어가는 중입니다</strong><small>최유진 → 박민서 · 수락 대기</small></div>
            </div>
          </figure>
          <figure className="landing-screen">
            <figcaption><strong>인수인계</strong><span>준비·전달·수락 세 단계로 다음 담당자에게 넘깁니다.</span></figcaption>
            <div className="landing-shot" aria-hidden="true">
              <span className="landing-shot-steps"><span className="is-current">준비</span><span>전달</span><span>수락</span></span>
              <b className="landing-shot-title">김준호님에게 넘길 인수인계</b>
              <span className="landing-shot-ratio"><small>1/2 항목 완료</small><b>50%</b></span>
              <span className="landing-shot-progress"><i /></span>
              <ul>
                <li className="is-done"><span className="landing-shot-check"><Icon name="check" size={12} /></span>역할의 한 줄 목적</li>
                <li><span className="landing-shot-check" />자주 생기는 문제와 대응법</li>
              </ul>
            </div>
          </figure>
          <figure className="landing-screen">
            <figcaption><strong>기록</strong><span>결정과 이유, 검토한 대안을 함께 남깁니다.</span></figcaption>
            <div className="landing-shot" aria-hidden="true">
              <small>2026. 7. 3. 오후 9:00 · 박민서</small>
              <b className="landing-shot-title">한 회차의 문제 수를 5개로 정한다</b>
              <dl>
                <dt>이유</dt><dd>풀이를 비교하는 시간을 확보하기 위해서입니다.</dd>
                <dt>검토한 대안</dt><dd>모임 시간을 늘리기</dd>
              </dl>
              <span className="landing-shot-chip">문제 큐레이터</span>
            </div>
          </figure>
        </div>
      </section>
    </>
  )
}
