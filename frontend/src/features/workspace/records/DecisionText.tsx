import Markdown from 'react-markdown'
import './decision-text.scss'

function isSafeLink(url: string) {
  try {
    const parsed = new URL(url)
    return ['http:', 'https:'].includes(parsed.protocol) && !parsed.username && !parsed.password
  } catch {
    return false
  }
}

export function DecisionText({ text, format }: { text: string; format: 'PLAIN_TEXT' | 'MARKDOWN' }) {
  if (format === 'PLAIN_TEXT') return <p className="decision-plain-text">{text}</p>
  return (
    <div className="decision-markdown">
      <Markdown
        skipHtml
        disallowedElements={['img']}
        urlTransform={(url) => (isSafeLink(url) ? url : '')}
        components={{
          a: ({ href, children }) => href
            ? <a href={href} target="_blank" rel="noopener noreferrer">{children}</a>
            : <span>{children}</span>,
        }}
      >{text}</Markdown>
    </div>
  )
}
