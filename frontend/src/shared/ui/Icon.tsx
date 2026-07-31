import type { ReactNode } from 'react'

type IconName =
  | 'today'
  | 'roles'
  | 'rhythm'
  | 'memory'
  | 'handoff'
  | 'search'
  | 'chevron'
  | 'plus'
  | 'check'
  | 'alert'
  | 'arrow'
  | 'close'
  | 'spark'
  | 'menu'

type IconProps = {
  name: IconName
  size?: number
  strokeWidth?: number
  className?: string
}

export function Icon({ name, size = 18, strokeWidth = 1.8, className }: IconProps) {
  const common = {
    width: size,
    height: size,
    viewBox: '0 0 24 24',
    fill: 'none',
    stroke: 'currentColor',
    strokeWidth,
    strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const,
    className,
    'aria-hidden': true,
  }

  const paths: Record<IconName, ReactNode> = {
    today: <><path d="M4 5.5h16v14H4z" /><path d="M8 3v5M16 3v5M4 9.5h16" /><path d="m8.5 14 2 2 4.5-5" /></>,
    roles: <><circle cx="7" cy="7" r="2.5" /><circle cx="17" cy="7" r="2.5" /><circle cx="12" cy="17" r="2.5" /><path d="M9 8.5l2 6M15 8.5l-2 6M9.5 7h5" /></>,
    rhythm: <><path d="M4 6h10M4 12h16M10 18h10" /><circle cx="17" cy="6" r="2" /><circle cx="7" cy="18" r="2" /></>,
    memory: <><path d="M6 4.5h9.5a2 2 0 0 1 2 2V20H8a2 2 0 0 1-2-2z" /><path d="M6 17.5a2 2 0 0 1 2-2h9.5M9.5 8h4" /></>,
    handoff: <><path d="M5 8.5h8.5a3 3 0 0 1 3 3v0a3 3 0 0 1-3 3H8" /><path d="m10.5 5.5 3 3-3 3M10.5 11.5l-3 3 3 3" /><path d="M19 6v12" /></>,
    search: <><circle cx="10.5" cy="10.5" r="6" /><path d="m15 15 4.5 4.5" /></>,
    chevron: <path d="m9 6 6 6-6 6" />,
    plus: <><path d="M12 5v14M5 12h14" /></>,
    check: <path d="m5 12.5 4.2 4L19 7" />,
    alert: <><path d="M12 4 3.7 18.5h16.6z" /><path d="M12 9v4M12 16.2v.1" /></>,
    arrow: <><path d="M5 12h14" /><path d="m14 7 5 5-5 5" /></>,
    close: <><path d="m6 6 12 12M18 6 6 18" /></>,
    spark: <><path d="M12 3.5 13.5 9l5.5 1.5-5.5 1.5-1.5 5.5-1.5-5.5L5 10.5 10.5 9z" /><path d="m18 15 .7 2.3L21 18l-2.3.7L18 21l-.7-2.3L15 18l2.3-.7z" /></>,
    menu: <><path d="M4 7h16M4 12h16M4 17h16" /></>,
  }

  return <svg {...common}>{paths[name]}</svg>
}
