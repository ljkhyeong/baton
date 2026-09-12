# CAL 구독·복구 진단 스키마

이 폴더의 스키마는 공식 불변 사전 릴리스 `1.1.0-rc.2`의 ZIP과 바이트가 같다.
상위 `pin.properties`에 공식 버전·태그·자산 SHA-256을, `source.properties`에 원본 커밋과
각 파일의 해시를 고정한다. 기존 테스트 경로를 유지하기 위해 폴더 이름은 `candidate`로 둔다.

`bash ops/tests/calendar-consumer-contract.sh`는 기본으로 `1.1.0-rc.2`를 검증한다.
구독 기능은 기본 비활성이며 실제 CAL 배포와 앱 구독 검증 후 활성화한다.
