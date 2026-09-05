# BRIEF 이벤트 계약 팩 고정 기준

BATON은 BRIEF 이벤트 v2 생산자 구현을 위해 `2.0.0-rc.4` 계약 팩의 요청 스키마와 예시를
이 저장소에 고정한다. 원본은 BRIEF `1e9dd22`에서 생성한
`baton-brief-contracts-2.0.0-rc.4.zip`이며 SHA-256은 다음과 같다.

```text
c665192b2b42e1b47b8deed1f44e4a627f3d3523b618d80b45bb4a3e3f961fc0
```

`adapter-out-external`의 계약 테스트는 Java record를 Jackson으로 직렬화한 결과가 고정
예시와 같고 JSON Schema를 통과하는지 확인한다. 별도 선택 실행 테스트는 실제 신호
스트림·transactional outbox·송신기와 BRIEF 수신기의 전용 Bearer, 새·직전 token 중첩
구간까지 로컬에서 검증했다. 실제 HTTPS 스테이징과 계약 팩 안정 버전 승격을 뜻하지는
않는다.

새 계약 버전을 적용할 때는 기존 디렉터리를 덮어쓰지 않고 새 버전 디렉터리를 추가한 뒤
생산자 모델·계약 테스트와 PRD-0007을 함께 갱신한다.

`2.0.0-rc.1`은 이전 고정 기록으로 보존한다. 현재 테스트는 `2.0.0-rc.4`의 같은 일곱 예시와
Schema를 읽으며, 공백·Unicode와 부호 있는 64비트 리비전 범위를 소비자 계약에 맞춘다.
생산자 이벤트 모델·outbox·송신기를 바꾸거나 별도 문자열 검증기를 추가하지 않는다.
