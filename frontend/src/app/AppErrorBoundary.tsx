import { Component, type ReactNode } from 'react'
import ServiceStatusLink from '@/shared/ui/ServiceStatusLink'

type AppErrorBoundaryProps = {
  children: ReactNode
}

type AppErrorBoundaryState = {
  failed: boolean
}

export default class AppErrorBoundary extends Component<
  AppErrorBoundaryProps,
  AppErrorBoundaryState
> {
  state: AppErrorBoundaryState = { failed: false }

  static getDerivedStateFromError(): AppErrorBoundaryState {
    return { failed: true }
  }

  render() {
    if (!this.state.failed) return this.props.children

    return (
      <>
        <title>오류 — BATON</title>
        <main className="remote-state-page">
          <section className="remote-state" role="alert">
            <span className="section-kicker">화면 복구 필요</span>
            <h1>화면을 불러오지 못했습니다.</h1>
            <p>잠시 뒤 다시 불러오거나 처음 화면으로 이동해 주세요.</p>
            <div className="remote-state-actions">
              <button
                className="primary-button"
                type="button"
                onClick={() => window.location.reload()}
              >
                다시 불러오기
              </button>
              <a className="secondary-button" href="/">처음 화면으로 이동</a>
              <ServiceStatusLink className="secondary-button" />
            </div>
          </section>
        </main>
      </>
    )
  }
}
