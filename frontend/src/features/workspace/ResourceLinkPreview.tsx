import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { getResourceLinkPreview, type WorkspaceScope } from './api'
import type { ResourceLinkPreview as Preview } from './types'

export function ResourceLinkPreview({ scope, url, disabled, onResolved }: {
  scope: WorkspaceScope | null
  url: string
  disabled: boolean
  onResolved: (preview: Preview) => void
}) {
  const [settledUrl, setSettledUrl] = useState('')
  const applied = useRef<Preview | null>(null)
  useEffect(() => {
    applied.current = null
    const timer = window.setTimeout(() => setSettledUrl(url.trim()), 450)
    return () => window.clearTimeout(timer)
  }, [url])
  const parsed = URL.parse(settledUrl)
  const supported = Boolean(parsed && ['http:', 'https:'].includes(parsed.protocol)
    && ['youtube.com', 'www.youtube.com', 'm.youtube.com', 'youtu.be',
      'vimeo.com', 'www.vimeo.com', 'player.vimeo.com'].includes(parsed.hostname))
  const enabled = Boolean(scope && supported && !disabled && settledUrl === url.trim())
  const query = useQuery({
    queryKey: ['resource-link-preview', scope?.accountId, scope?.teamId, scope?.seasonId,
      scope?.accessKey, settledUrl],
    queryFn: ({ signal }) => getResourceLinkPreview(scope!, settledUrl, signal),
    enabled,
    retry: false,
    staleTime: 5 * 60_000,
    refetchOnWindowFocus: false,
  })
  useEffect(() => {
    if (enabled && query.data && applied.current !== query.data) {
      applied.current = query.data
      onResolved(query.data)
    }
  }, [enabled, query.data, onResolved])

  return <p className="field-help" role="status">
    {enabled && query.isFetching ? '제목과 썸네일을 가져오는 중…'
      : enabled && (query.isError || (query.isSuccess && !query.data?.title))
        ? '정보를 가져오지 못했습니다. 자료 이름을 직접 입력해 주세요.'
        : 'YouTube·Vimeo 링크는 제목과 썸네일을 자동으로 가져옵니다.'}
  </p>
}
