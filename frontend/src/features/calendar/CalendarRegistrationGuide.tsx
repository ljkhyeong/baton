import { useState } from 'react'

const guides = {
  google: {
    label: 'Google Calendar',
    instruction: 'PC에서 등록 화면을 열고 주소를 붙여 넣은 뒤 ‘캘린더 추가’를 누르세요. 모바일 앱에는 등록 메뉴가 없습니다.',
    href: 'https://calendar.google.com/calendar/u/0/r/settings/addbyurl',
    linkLabel: 'Google 등록 화면 열기',
  },
  apple: {
    label: 'Apple 캘린더',
    instruction: 'iPhone은 캘린더 → 캘린더 추가 → 구독 캘린더 추가, Mac은 파일 → 새로운 캘린더 구독에서 주소를 붙여 넣으세요.',
    href: 'https://support.apple.com/ko-kr/guide/iphone/iph3d1110d4/ios',
    linkLabel: 'Apple 등록 안내 보기',
  },
  outlook: {
    label: 'Outlook 개인 계정',
    instruction: 'Outlook에서 캘린더 추가 → 웹에서 구독을 선택하고 주소를 붙여 넣으세요.',
    href: 'https://outlook.live.com/calendar/',
    linkLabel: 'Outlook 열기',
  },
  outlookWork: {
    label: 'Outlook 회사·학교 계정',
    instruction: 'Outlook에서 캘린더 추가 → 웹에서 구독을 선택하고 주소를 붙여 넣으세요.',
    href: 'https://outlook.office.com/calendar/',
    linkLabel: '회사·학교 Outlook 열기',
  },
}

export function CalendarRegistrationGuide({ feedUrl }: { feedUrl?: string }) {
  const [provider, setProvider] = useState<keyof typeof guides>('google')
  const [copied, setCopied] = useState('')
  const guide = guides[provider]

  async function copyAddress() {
    if (!feedUrl) return
    try { await navigator.clipboard.writeText(feedUrl); setCopied('구독 주소를 복사했습니다.') }
    catch { setCopied('복사하지 못했습니다. 주소를 선택해 직접 복사해 주세요.') }
  }

  return <details className="calendar-guide" open={Boolean(feedUrl)}>
    <summary>캘린더 앱에 등록하는 방법</summary>
    <label>사용할 캘린더
      <select value={provider} onChange={event => setProvider(event.target.value as keyof typeof guides)}>
        {Object.entries(guides).map(([value, item]) => <option key={value} value={value}>{item.label}</option>)}
      </select>
    </label>
    {feedUrl ? <div className="calendar-address">
      <label>내 구독 주소<input readOnly type="text" value={feedUrl} autoComplete="off" spellCheck={false} onFocus={event => event.currentTarget.select()} /></label>
      <button type="button" className="primary-button" onClick={() => void copyAddress()}>구독 주소 복사</button>
      {copied && <p role="status">{copied}</p>}
      <p>이 주소를 아는 사람은 일정을 볼 수 있습니다. 공유하지 마세요. 화면을 닫으면 주소가 사라지며, 다시 필요하면 새 주소를 발급해야 합니다.</p>
    </div> : <p>새 구독 주소를 발급한 뒤 복사하세요. 기존 주소가 있다면 그대로 등록할 수 있습니다.</p>}
    <p>{guide.instruction}</p>
    <a href={guide.href} target="_blank" rel="noopener noreferrer">{guide.linkLabel} (새 탭)</a>
    <p>URL 구독으로 등록해야 이후 변경이 반영됩니다. 갱신 주기는 캘린더 앱마다 다르며 즉시 반영되지 않을 수 있습니다.</p>
  </details>
}
