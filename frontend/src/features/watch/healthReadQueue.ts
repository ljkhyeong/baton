import { ApiClientError } from '@/shared/api/ApiError'

type WaitingRead = { start: () => void }
const waiting: WaitingRead[] = []
let active = 0
let nextStartAt = 0
let timer: ReturnType<typeof setTimeout> | undefined

function drain() {
  if (timer !== undefined || active >= 2 || waiting.length === 0) return
  const delay = nextStartAt - performance.now()
  if (delay > 0) {
    timer = setTimeout(() => { timer = undefined; drain() }, delay)
    return
  }
  waiting.shift()!.start()
  drain()
}

// 열린 화면의 조회만 순서대로 보내고, 닫힌 화면의 대기 요청은 취소 신호로 제거한다.
export function scheduleHealthRead<T>(read: (signal: AbortSignal) => Promise<T>, signal?: AbortSignal): Promise<T> {
  const deadline = AbortSignal.timeout(30_000)
  const queuedSignal = signal ? AbortSignal.any([signal, deadline]) : deadline
  if (queuedSignal.aborted) return Promise.reject(queuedSignal.reason)
  if (waiting.length >= 100) return Promise.reject(new ApiClientError('timeout', undefined))

  return new Promise((resolve, reject) => {
    const cancel = () => {
      const index = waiting.indexOf(entry)
      if (index >= 0) waiting.splice(index, 1)
      reject(queuedSignal.reason)
      drain()
    }
    const entry: WaitingRead = {
      start: () => {
        queuedSignal.removeEventListener('abort', cancel)
        active++
        nextStartAt = performance.now() + 250
        Promise.resolve().then(() => read(queuedSignal)).then(resolve, reject)
          .finally(() => { active--; drain() })
      },
    }
    queuedSignal.addEventListener('abort', cancel, { once: true })
    waiting.push(entry)
    drain()
  })
}
