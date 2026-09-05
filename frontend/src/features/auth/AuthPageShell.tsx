import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'

type AuthPageShellProps = {
  eyebrow: string
  title: string
  description: string
  children: ReactNode
}

export default function AuthPageShell({
  eyebrow,
  title,
  description,
  children,
}: AuthPageShellProps) {
  return (
    <main className="auth-page">
      <section className="auth-story" aria-labelledby="auth-story-title">
        <Link className="brand auth-brand" to="/" aria-label="BATON 시작 화면">
          <span className="brand-mark" aria-hidden="true" />
          BATON
        </Link>
        <div className="auth-story-copy">
          <span className="section-kicker">BATON 계정</span>
          <h1 id="auth-story-title">사용할 로그인 방법을 선택하세요.</h1>
          <p>
            Google, Naver, 자체 이메일 중 편한 방법으로 들어오세요.
            같은 이메일이어도 공급자가 다르면 안전을 위해 자동으로 계정을 합치지 않습니다.
          </p>
          <div className="auth-identity-rail" aria-label="BATON에서 사용할 수 있는 로그인 수단">
            <span>Google</span>
            <span>Naver</span>
            <span>Email</span>
            <strong>BATON Account</strong>
          </div>
        </div>
        <small className="auth-story-note">
          공급자 토큰과 비밀번호는 브라우저 저장소에 남기지 않습니다.
        </small>
      </section>

      <section className="auth-panel">
        <div className="auth-panel-inner">
          <header className="auth-heading">
            <span className="section-kicker">{eyebrow}</span>
            <h2>{title}</h2>
            <p>{description}</p>
          </header>
          {children}
        </div>
      </section>
    </main>
  )
}
