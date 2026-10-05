import { Link, NavLink } from 'react-router-dom'
import '@/features/team-access/my-teams.scss'

export default function AccountTopbar() {
  return (
    <header className="account-topbar">
      <Link className="brand" to="/" aria-label="BATON 시작 화면"><span className="brand-mark" aria-hidden="true" />BATON</Link>
      <nav aria-label="계정 메뉴">
        <NavLink to="/my-teams">내 팀</NavLink>
        <NavLink to="/account">내 계정</NavLink>
      </nav>
    </header>
  )
}
