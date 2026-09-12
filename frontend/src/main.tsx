import { StrictMode } from 'react'
import { initializeErrorReporting, reportReactError } from './app/errorReporting'
import { createRoot } from 'react-dom/client'
import App from '@/app/App'
import '@/styles/global.scss'

const root = document.getElementById('root')

if (!root) {
  throw new Error('애플리케이션 루트 요소를 찾을 수 없습니다.')
}

initializeErrorReporting()

createRoot(root, { onCaughtError: reportReactError, onUncaughtError: reportReactError }).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
