import { useEffect, useRef, useState } from 'react'

type Turnstile = {
  render: (container: HTMLElement, options: {
    sitekey: string
    action: string
    size: 'flexible'
    language: 'ko'
    'response-field': false
    callback: (token: string) => void
    'expired-callback': () => void
    'error-callback': () => void
    'timeout-callback': () => void
  }) => string
  remove: (widgetId: string) => void
}

declare global {
  interface Window {
    turnstile?: Turnstile
  }
}

let scriptPromise: Promise<Turnstile> | undefined

function loadTurnstile(): Promise<Turnstile> {
  if (window.turnstile) return Promise.resolve(window.turnstile)
  if (scriptPromise) return scriptPromise
  scriptPromise = new Promise<Turnstile>((resolve, reject) => {
    const script = document.createElement('script')
    script.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit'
    script.async = true
    const fail = () => {
      clearTimeout(timeout)
      script.onload = null
      script.onerror = null
      script.remove()
      reject(new Error('자동 요청 방지 확인을 불러오지 못했습니다.'))
    }
    const timeout = window.setTimeout(fail, 20_000)
    script.onerror = fail
    script.onload = () => {
      if (!window.turnstile) return fail()
      clearTimeout(timeout)
      resolve(window.turnstile)
    }
    document.head.append(script)
  }).catch((error: unknown) => {
    scriptPromise = undefined
    throw error
  })
  return scriptPromise
}

type Props = {
  siteKey: string
  action: 'local_registration' | 'password_reset_request'
  resetKey: number
  onTokenChange: (token: string | null) => void
}

export default function TurnstileWidget({ siteKey, action, resetKey, onTokenChange }: Props) {
  const container = useRef<HTMLDivElement>(null)
  const [attempt, setAttempt] = useState(0)
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading')

  useEffect(() => {
    onTokenChange(null)
    setStatus('loading')
    let active = true
    let api: Turnstile | undefined
    let widgetId: string | undefined
    const fail = () => {
      if (!active) return
      onTokenChange(null)
      setStatus('error')
    }
    void loadTurnstile().then((loaded) => {
      if (!active || !container.current) return
      api = loaded
      widgetId = loaded.render(container.current, {
        sitekey: siteKey,
        action,
        size: 'flexible',
        language: 'ko',
        'response-field': false,
        callback: (token) => {
          if (!active) return
          onTokenChange(token)
          setStatus('ready')
        },
        'expired-callback': () => {
          if (!active) return
          onTokenChange(null)
          setStatus('loading')
        },
        'error-callback': fail,
        'timeout-callback': fail,
      })
    }).catch(fail)
    return () => {
      active = false
      if (widgetId !== undefined) api?.remove(widgetId)
    }
  }, [siteKey, action, onTokenChange, attempt, resetKey])

  return (
    <div className="auth-turnstile">
      <div ref={container} />
      {status === 'loading' && <p role="status">자동 요청 방지 확인 중…</p>}
      {status === 'error' && (
        <div>
          <p className="form-error" role="alert">자동 요청 방지 확인을 완료하지 못했습니다.</p>
          <button type="button" className="secondary-button" onClick={() => {
            onTokenChange(null)
            setStatus('loading')
            setAttempt(value => value + 1)
          }}>다시 확인</button>
        </div>
      )}
    </div>
  )
}
