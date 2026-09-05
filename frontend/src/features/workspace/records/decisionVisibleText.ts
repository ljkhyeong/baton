import { fromMarkdown } from 'mdast-util-from-markdown'

type MarkdownNode = { type: string; value?: string; children?: MarkdownNode[] }

function visibleText(node: MarkdownNode): string {
  if (['html', 'definition', 'image', 'imageReference'].includes(node.type)) return ''
  if (node.type === 'break') return '\n'
  if (node.value !== undefined) return node.value
  const separator = ['root', 'list', 'listItem', 'blockquote'].includes(node.type) ? '\n' : ''
  return node.children?.map(visibleText).join(separator) ?? ''
}

export function decisionVisibleText(text: string, format: 'PLAIN_TEXT' | 'MARKDOWN') {
  return format === 'PLAIN_TEXT' ? text : visibleText(fromMarkdown(text))
}
