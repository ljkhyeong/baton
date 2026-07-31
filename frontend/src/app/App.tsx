import { QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import AccountBoundaryGuard from '@/features/identity/AccountBoundaryGuard'
import OnboardingPage from '@/pages/OnboardingPage'
import WorkspacePage from '@/pages/WorkspacePage'
import { queryClient } from '@/shared/api/queryClient'

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AccountBoundaryGuard>
          <Routes>
            <Route path="/" element={<OnboardingPage />} />
            <Route path="/teams/:teamId/seasons/:seasonId" element={<WorkspacePage />} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </AccountBoundaryGuard>
      </BrowserRouter>
    </QueryClientProvider>
  )
}
