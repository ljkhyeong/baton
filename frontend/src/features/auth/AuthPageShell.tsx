import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import ServiceStatusLink from '@/shared/ui/ServiceStatusLink'

type AuthPageShellProps = {
  title: string
  description: string
  children: ReactNode
}

export default function AuthPageShell({
  title,
  description,
  children,
}: AuthPageShellProps) {
  return (
    <main className="auth-page">
      <aside className="auth-story" aria-label="BATON 소개">
        <Link className="brand auth-brand" to="/" aria-label="BATON 시작 화면">
          <span className="brand-mark" aria-hidden="true" />
          BATON
        </Link>
        <div className="auth-story-copy">
          <p className="auth-story-title">담당 업무부터<br />인수인계까지.</p>
          <p>담당 업무, 결정 이유, 인수인계 자료를 한곳에서 관리하세요.</p>
        </div>
      </aside>

      <section className="auth-panel" aria-labelledby="auth-title">
        <div className="auth-panel-inner">
          <header className="auth-heading">
            <h1 id="auth-title">{title}</h1>
            <p>{description}</p>
          </header>
          {children}
          <p className="auth-footnote">같은 이메일이어도 로그인 방법이 다르면 별도 계정입니다.</p>
        </div>
      </section>
      <ServiceStatusLink className="service-status-link auth-status-link" />
    </main>
  )
}
