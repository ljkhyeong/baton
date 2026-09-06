import { isJsonObject } from '@/shared/api/responseValidation'
import type { ResourceLinkPreview } from './types'

export function isResourceThumbnail(value: unknown): value is string | null | undefined {
  if (value === null || value === undefined) return true
  if (typeof value !== 'string' || value.length > 2048) return false
  const url = URL.parse(value)
  return Boolean(url && url.protocol === 'https:' && !url.username && !url.password && !url.port
    && ['i.ytimg.com', 'i.vimeocdn.com', 'secure-b.vimeocdn.com'].includes(url.hostname))
}

export function decodeResourceLinkPreview(value: unknown): ResourceLinkPreview {
  if (!isJsonObject(value)
    || !(value.title === null || (typeof value.title === 'string' && value.title.length <= 200))
    || !isResourceThumbnail(value.thumbnailUrl)) {
    throw new TypeError('자료 제목과 썸네일을 확인하지 못했습니다.')
  }
  return value as ResourceLinkPreview
}
