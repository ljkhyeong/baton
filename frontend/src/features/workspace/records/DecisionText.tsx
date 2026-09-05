import Markdown from 'react-markdown'
import './decision-text.scss'

export function DecisionText({ text, format }: { text: string; format: 'PLAIN_TEXT' | 'MARKDOWN' }) {
  if (format === 'PLAIN_TEXT') return <p className="decision-plain-text">{text}</p>
  return (
    <div className="decision-markdown">
      <Markdown
        skipHtml
        disallowedElements={['img']}
        urlTransform={(url) => {
          const parsed = URL.parse(url)
          return parsed && ['http:', 'https:'].includes(parsed.protocol) && !parsed.username && !parsed.password
            ? url : ''
        }}
        components={{
          a: ({ href, children }) => href
            ? <a href={href} target="_blank" rel="noopener noreferrer">{children}</a>
            : <span>{children}</span>,
        }}
      >{text}</Markdown>
    </div>
  )
}
