import type { BrowserOptions } from '@sentry/react'

type ReactErrorHandler = ReturnType<typeof import('@sentry/react')['reactErrorHandler']>
let reactErrorHandler: ReactErrorHandler | undefined
let ready = Promise.resolve()

function errorFileUrl(filename?: string) {
  if (!filename) return null
  try {
    const url = new URL(filename, window.location.origin)
    return url.origin === window.location.origin && /^\/(assets|src)\//.test(url.pathname)
      ? url.origin + url.pathname : null
  } catch {
    return null
  }
}

// 요청·화면 주소와 오류 메시지를 제외하고 배포 파일의 오류 위치만 수집한다.
export const errorLocationOnly: NonNullable<BrowserOptions['beforeSend']> = (event, hint) => {
  hint.attachments = []
  if (!event.exception?.values?.length) return null
  const values = event.exception.values.map((exception) => ({
    type: /^[\w.$]{1,100}$/.test(exception.type ?? '') ? exception.type : 'Error',
    stacktrace: {
      frames: exception.stacktrace?.frames?.flatMap((frame) => {
        const filename = errorFileUrl(frame.filename)
        return filename ? [{ filename, lineno: frame.lineno, colno: frame.colno,
          function: /^[\w.$<> ]{1,120}$/.test(frame.function ?? '') ? frame.function : undefined,
          in_app: true }] : []
      }),
    },
  }))
  const files = new Set(values.flatMap((exception) => exception.stacktrace.frames?.map((frame) => frame.filename) ?? []))
  const images = event.debug_meta?.images?.flatMap((image) => {
    if (image.type !== 'sourcemap' || !/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i.test(image.debug_id)) return []
    const codeFile = errorFileUrl(image.code_file)
    return codeFile && files.has(codeFile)
      ? [{ type: 'sourcemap' as const, code_file: codeFile, debug_id: image.debug_id }] : []
  })
  return {
    type: undefined,
    event_id: event.event_id,
    timestamp: event.timestamp,
    platform: 'javascript',
    level: event.level,
    environment: event.environment,
    release: event.release,
    exception: { values },
    debug_meta: images?.length ? { images } : undefined,
  }
}

export function initializeErrorReporting(dsn = import.meta.env.VITE_SENTRY_DSN?.trim()) {
  if (!dsn) return ready
  ready = import('@sentry/react').then(({
    init, globalHandlersIntegration, browserApiErrorsIntegration, dedupeIntegration, reactErrorHandler: createReactErrorHandler,
  }) => {
    init({
      dsn,
      environment: import.meta.env.VITE_SENTRY_ENVIRONMENT || 'production',
      defaultIntegrations: false,
      integrations: [globalHandlersIntegration(), browserApiErrorsIntegration(), dedupeIntegration()],
      sendDefaultPii: false,
      sendClientReports: false,
      enableLogs: false,
      tracesSampleRate: 0,
      beforeSend: errorLocationOnly,
    })
    reactErrorHandler = createReactErrorHandler()
  }).catch(() => {
    console.warn('오류 수집 설정을 확인해 주세요.')
  })
  return ready
}

export const reportReactError: ReactErrorHandler = (error, errorInfo) => {
  void ready.then(() => reactErrorHandler?.(error, errorInfo))
}
