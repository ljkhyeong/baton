/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_WORKSPACE_SYNC_INTERVAL_MS?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
