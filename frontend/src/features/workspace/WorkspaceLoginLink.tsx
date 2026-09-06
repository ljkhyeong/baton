import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { readAccessKey } from './storage'

type WorkspaceLoginLinkProps = {
  teamId: string
  seasonId: string
  accessKey: string
  className?: string
  children: ReactNode
}

export default function WorkspaceLoginLink({
  teamId,
  seasonId,
  accessKey,
  className,
  children,
}: WorkspaceLoginLinkProps) {
  if (readAccessKey(teamId) !== accessKey) {
    return (
      <>
        <Link className={className} to="/login" target="_blank" rel="noopener noreferrer">
          {children} (새 탭)
        </Link>
        {' '}
        <span>공유 링크를 저장하지 못해 로그인을 새 탭에서 엽니다. 이 탭을 닫지 말고 로그인 후 돌아와 주세요.</span>
      </>
    )
  }

  return (
    <Link className={className} to={`/login?${new URLSearchParams({
      returnTo: `/teams/${teamId}/seasons/${seasonId}`,
    })}`}>
      {children}
    </Link>
  )
}
