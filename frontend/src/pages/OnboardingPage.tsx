import OnboardingForm from '@/features/onboarding/OnboardingForm'
import { useDocumentTitle } from '@/shared/lib/useDocumentTitle'

export default function OnboardingPage() {
  useDocumentTitle('BATON')
  return <OnboardingForm />
}
