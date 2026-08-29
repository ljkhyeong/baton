import { QueryClientProvider } from '@tanstack/react-query'
import { lazy, Suspense } from 'react'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { queryClient } from '@/shared/api/queryClient'
import AppErrorBoundary from './AppErrorBoundary'

const EmailVerificationPage = lazy(() => import('@/pages/EmailVerificationPage'))
const LoginPage = lazy(() => import('@/pages/LoginPage'))
const OnboardingPage = lazy(() => import('@/pages/OnboardingPage'))
const RegistrationPage = lazy(() => import('@/pages/RegistrationPage'))
const WorkspacePage = lazy(() => import('@/pages/WorkspacePage'))

function RouteFallback() {
  return (
    <main className="route-loading" aria-busy="true" aria-live="polite">
      화면을 준비하고 있습니다.
    </main>
  )
}

export default function App() {
  return (
    <AppErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <Suspense fallback={<RouteFallback />}>
            <Routes>
              <Route path="/" element={<OnboardingPage />} />
              <Route path="/login" element={<LoginPage />} />
              <Route path="/register" element={<RegistrationPage />} />
              <Route path="/verify-email" element={<EmailVerificationPage />} />
              <Route path="/teams/:teamId/seasons/:seasonId" element={<WorkspacePage />} />
              <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
          </Suspense>
        </BrowserRouter>
      </QueryClientProvider>
    </AppErrorBoundary>
  )
}
