# CAL 구독·복구 진단 계약 후보

이 폴더는 CAL `1.1.0-rc.2` 개발 소스에서 복사한 검증용 계약이다. `source.properties`에 원본 커밋과 각 파일의 SHA-256을 고정한다. 릴리스 게시나 운영 채택을 의미하지 않으며 상위 `pin.properties`의 게시된 `1.1.0-rc.1` 계약은 유지한다.

후보 검증은 `BATON_CAL_CONTRACT_VERSION=1.1.0-rc.2 bash ops/tests/calendar-consumer-contract.sh`로 실행한다. 기본 실행은 기존 `1.1.0-rc.1` 소스를 요구한다. 구독 기능은 기본 비활성이고 후보 검증 환경에서만 `BATON_CAL_SUBSCRIPTIONS_ENABLED=true`를 사용한다.
