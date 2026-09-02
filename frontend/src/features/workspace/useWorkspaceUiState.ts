import {
  useCallback,
  useEffect,
  useEffectEvent,
  useLayoutEffect,
  useRef,
  useState,
  useSyncExternalStore,
} from 'react'

export type WorkspaceModal = 'decision' | 'members' | 'member' | 'role' | 'roleResource' | 'routine' | 'round' | 'roundSchedule' | 'handoffItem' | 'roleHandoff' | 'handoffPreview' | 'shareLink' | 'accessKey' | 'seasonSwitcher' | 'seasonEdit' | 'seasonSuccessor' | null
export type OpenWorkspaceModal = Exclude<WorkspaceModal, null>
type Toast = { message: string; tone: 'success' | 'error' }

function useMediaQuery(query: string, onBeforeChange?: (matches: boolean) => void) {
  const notifyBeforeChange = useEffectEvent((matches: boolean) => {
    onBeforeChange?.(matches)
  })
  const subscribe = useCallback((notify: () => void) => {
    const mediaQuery = window.matchMedia(query)
    const updateMatches = (event: MediaQueryListEvent) => {
      notifyBeforeChange(event.matches)
      notify()
    }
    mediaQuery.addEventListener('change', updateMatches)
    return () => mediaQuery.removeEventListener('change', updateMatches)
  }, [query])
  const getSnapshot = useCallback(() => window.matchMedia(query).matches, [query])

  return useSyncExternalStore(subscribe, getSnapshot, () => false)
}

export function canReceiveWorkspaceFocus(element: HTMLElement | null) {
  return Boolean(element?.isConnected
    && !element.closest('[inert]')
    && !element.matches(':disabled')
    && element.getAttribute('aria-disabled') !== 'true'
    && element.getClientRects().length > 0)
}

export function focusWorkspaceElement(preferred: HTMLElement | null) {
  const candidates = [
    preferred,
    ...document.querySelectorAll<HTMLElement>(
      '.inspector:not([inert]) .inspector-close, .main-surface',
    ),
  ]
  for (const candidate of candidates) {
    if (!canReceiveWorkspaceFocus(candidate)) continue
    candidate?.focus()
    if (document.activeElement === candidate) return
  }
}

export function useWorkspaceModalSession() {
  const [modal, setModal] = useState<WorkspaceModal>(null)
  const modalRef = useRef<WorkspaceModal>(null)
  const openerRef = useRef<HTMLElement | null>(null)
  const generationRef = useRef(0)

  const openModal = (nextModal: OpenWorkspaceModal) => {
    if (modalRef.current === null) {
      generationRef.current += 1
      openerRef.current = document.activeElement instanceof HTMLElement
        ? document.activeElement
        : null
    }
    modalRef.current = nextModal
    setModal(nextModal)
  }

  const closeModal = () => {
    if (modalRef.current === null) return

    const generation = generationRef.current
    const opener = openerRef.current
    modalRef.current = null
    setModal(null)
    window.requestAnimationFrame(() => {
      if (modalRef.current !== null || generationRef.current !== generation) return
      focusWorkspaceElement(opener)
      openerRef.current = null
    })
  }

  return { modal, openModal, closeModal }
}

export function useWorkspaceRecordBusyIds() {
  const busyIdsRef = useRef<ReadonlySet<string>>(new Set())
  const [busyIds, setBusyIds] = useState<ReadonlySet<string>>(busyIdsRef.current)

  const begin = (id: string) => {
    if (busyIdsRef.current.has(id)) return false
    const next = new Set(busyIdsRef.current)
    next.add(id)
    busyIdsRef.current = next
    setBusyIds(next)
    return true
  }

  const end = (id: string) => {
    if (!busyIdsRef.current.has(id)) return
    const next = new Set(busyIdsRef.current)
    next.delete(id)
    busyIdsRef.current = next
    setBusyIds(next)
  }

  return { busyIds, begin, end }
}

export function useWorkspaceToast() {
  const [toast, setToast] = useState<Toast | null>(null)
  const timeoutIdRef = useRef<number | null>(null)

  useEffect(() => () => {
    if (timeoutIdRef.current !== null) window.clearTimeout(timeoutIdRef.current)
  }, [])

  const showToast = (message: string, tone: Toast['tone'] = 'success') => {
    if (timeoutIdRef.current !== null) window.clearTimeout(timeoutIdRef.current)
    setToast({ message, tone })
    timeoutIdRef.current = window.setTimeout(() => {
      setToast(null)
      timeoutIdRef.current = null
    }, 2800)
  }

  return { toast, showToast }
}

export function useWorkspaceInspectorSession() {
  const [inspectorOpen, setInspectorOpen] = useState(false)
  const inspectorOpenRef = useRef(false)
  const inspectorOpenerRef = useRef<HTMLElement | null>(null)
  const focusGenerationRef = useRef(0)
  const modeChangeHadFocusRef = useRef(false)
  const inspectorOverlay = useMediaQuery('(max-width: 1240px)', () => {
    const activeElement = document.activeElement
    modeChangeHadFocusRef.current = activeElement instanceof HTMLElement
      && Boolean(activeElement.closest('.inspector'))
  })

  useLayoutEffect(() => {
    if (!modeChangeHadFocusRef.current) return

    modeChangeHadFocusRef.current = false
    const target = inspectorOverlay && inspectorOpenRef.current
      ? document.querySelector<HTMLElement>('.inspector:not([inert]) .inspector-close')
      : document.querySelector<HTMLElement>('.main-surface')
    focusWorkspaceElement(target)
  }, [inspectorOverlay])

  const dismissInspector = (restoreFocus: boolean) => {
    if (!inspectorOpenRef.current) return

    const generation = focusGenerationRef.current
    const opener = inspectorOpenerRef.current
    inspectorOpenRef.current = false
    setInspectorOpen(false)

    if (!restoreFocus) {
      focusGenerationRef.current += 1
      inspectorOpenerRef.current = null
      return
    }

    window.requestAnimationFrame(() => {
      if (inspectorOpenRef.current || focusGenerationRef.current !== generation) return
      focusWorkspaceElement(opener)
      if (opener
        && canReceiveWorkspaceFocus(opener)
        && document.activeElement !== opener) {
        window.requestAnimationFrame(() => {
          if (inspectorOpenRef.current || focusGenerationRef.current !== generation) return
          focusWorkspaceElement(opener)
        })
      }
      inspectorOpenerRef.current = null
    })
  }

  const openInspector = (opener?: HTMLElement) => {
    if (inspectorOverlay && !inspectorOpenRef.current) {
      focusGenerationRef.current += 1
      inspectorOpenerRef.current = opener
        ?? (document.activeElement instanceof HTMLElement ? document.activeElement : null)
    }
    inspectorOpenRef.current = true
    setInspectorOpen(true)
  }

  return {
    inspectorOpen,
    inspectorOverlay,
    dismissInspector,
    openInspector,
  }
}
