import { useEffect, useRef } from 'react'

export function useWorkspacePrint(briefEditionId?: string) {
  const printLocation = useRef<{ originalUrl: string; printUrl: string } | null>(null)
  useEffect(() => {
    const restoreLocation = () => {
      const saved = printLocation.current
      if (saved && window.location.href === saved.printUrl) {
        window.history.replaceState(window.history.state, '', saved.originalUrl)
      }
      printLocation.current = null
    }
    window.addEventListener('afterprint', restoreLocation)
    return () => {
      window.removeEventListener('afterprint', restoreLocation)
      restoreLocation()
    }
  }, [])
  return () => {
    const url = new URL(window.location.href)
    if (briefEditionId) {
      url.search = new URLSearchParams({ brief: briefEditionId }).toString()
      url.hash = ''
    } else if (new URLSearchParams(url.hash.slice(1)).has('accessKey')) {
      url.hash = ''
    }
    if (url.href !== window.location.href) {
      // 브라우저 인쇄 머리말에는 접근 키 대신 출력할 문서의 주소만 남긴다.
      printLocation.current = { originalUrl: window.location.href, printUrl: url.href }
      window.history.replaceState(window.history.state, '', url.href)
    }
    window.print()
  }
}
