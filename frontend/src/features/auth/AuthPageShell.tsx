import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import ServiceStatusLink from '@/shared/ui/ServiceStatusLink'

type AuthPageShellProps = {
  formTitle: string
  title: string
  description: string
  children: ReactNode
}

export default function AuthPageShell({
  formTitle,
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
          <h1 id="auth-story-title">{title}</h1>
          <p>
            같은 이메일이어도 로그인 방법이 다르면 별도 계정입니다.
          </p>
        </div>
        <ServiceStatusLink />
      </section>

      <section className="auth-panel">
        <div className="auth-panel-inner">
          <header className="auth-heading">
            <h2>{formTitle}</h2>
            <p>{description}</p>
          </header>
          {children}
        </div>
      </section>
    </main>
  )
}
