import { useEffectEvent, useLayoutEffect } from 'react'
import type { RefObject } from 'react'

const focusableSelector = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[contenteditable="true"]',
  '[tabindex]:not([tabindex="-1"])',
].join(',')

function focusableElements(container: HTMLElement) {
  return Array.from(container.querySelectorAll<HTMLElement>(focusableSelector))
    .filter((element) =>
      !element.closest('[inert]')
      && element.getAttribute('aria-hidden') !== 'true'
      && element.getClientRects().length > 0)
}

type UseFocusBoundaryOptions = {
  active: boolean
  closeDisabled?: boolean
  closeGuardRef?: RefObject<boolean>
  containerRef: RefObject<HTMLElement | null>
  initialFocusRef?: RefObject<HTMLElement | null>
  onClose: () => void
}

export function useFocusBoundary({
  active,
  closeDisabled = false,
  closeGuardRef,
  containerRef,
  initialFocusRef,
  onClose,
}: UseFocusBoundaryOptions) {
  const close = useEffectEvent(() => {
    if (!closeDisabled && !closeGuardRef?.current) onClose()
  })

  useLayoutEffect(() => {
    if (!active) return

    const container = containerRef.current
    if (!container) return

    if (!container.contains(document.activeElement)) {
      const initialTarget = initialFocusRef?.current ?? container
      initialTarget.focus()
    }

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        event.stopPropagation()
        close()
        return
      }

      if (event.key !== 'Tab') return

      const focusable = focusableElements(container)
      if (!focusable.length) {
        event.preventDefault()
        container.focus()
        return
      }

      const first = focusable[0]
      const last = focusable.at(-1)
      const activeElement = document.activeElement
      const activeIndex = activeElement instanceof HTMLElement
        ? focusable.indexOf(activeElement)
        : -1

      if (activeIndex < 0) {
        event.preventDefault()
        const boundaryTarget = event.shiftKey ? last : first
        boundaryTarget?.focus()
        return
      }

      if (event.shiftKey && activeElement === first) {
        event.preventDefault()
        last?.focus()
        return
      }

      if (!event.shiftKey && activeElement === last) {
        event.preventDefault()
        first?.focus()
      }
    }

    document.addEventListener('keydown', handleKeyDown, true)
    return () => {
      document.removeEventListener('keydown', handleKeyDown, true)
    }
  }, [active, closeGuardRef, containerRef, initialFocusRef])
}
