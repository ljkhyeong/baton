import OnboardingForm from '@/features/onboarding/OnboardingForm'
import OnboardingIntro from '@/features/onboarding/OnboardingIntro'
import ServiceStatusLink from '@/shared/ui/ServiceStatusLink'

export default function OnboardingPage() {
  return (
    <>
      <title>BATON</title>
      <main className="onboarding-page">
        <OnboardingIntro />
        <div className="landing-start" id="start">
          <div className="landing-start-copy">
            <p className="landing-eyebrow">시작하기</p>
            <p className="landing-start-lead">다음 담당자가 처음부터 묻지 않도록.</p>
            <p>빈 구성이나 스터디·팀 운영·TF 템플릿으로 시작하고, 만든 뒤 역할과 일정을 고칠 수 있습니다.</p>
          </div>
          <OnboardingForm />
        </div>
        <footer className="landing-footer">
          <span className="brand"><span className="brand-mark" />BATON</span>
          <ServiceStatusLink />
        </footer>
      </main>
    </>
  )
}
