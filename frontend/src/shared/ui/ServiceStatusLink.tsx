export default function ServiceStatusLink({ className = 'service-status-link' }: { className?: string }) {
  if (import.meta.env.VITE_STATUS_PAGE_ENABLED !== 'true') return null

  return (
    <a
      className={className}
      href="https://status.b4ton.com"
      target="_blank"
      rel="noopener noreferrer"
      referrerPolicy="no-referrer"
      aria-label="서비스 상태 (새 탭)"
    >
      서비스 상태
    </a>
  )
}
