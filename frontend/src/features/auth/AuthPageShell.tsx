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
          <span className="section-kicker">계정</span>
          <h1 id="auth-story-title">로그인 방법 선택</h1>
          <p>
            Google, Naver, 이메일 중 로그인 방법을 선택하세요.
            같은 이메일이어도 로그인 방법이 다르면 별도 계정입니다.
          </p>
          <div className="auth-identity-rail" aria-label="BATON에서 사용할 수 있는 로그인 방법">
            <span>Google</span>
            <span>Naver</span>
            <span>이메일</span>
            <strong>BATON 계정</strong>
          </div>
        </div>
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
