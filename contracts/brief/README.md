# BRIEF 이벤트 계약 팩 고정 기준

BATON은 BRIEF 이벤트 v2 생산자 구현을 위해 `2.0.0-rc.1` 계약 팩의 요청 스키마와 예시를
이 저장소에 고정한다. 원본은 BRIEF `df89f82`의
`baton-brief-contracts-2.0.0-rc.1.zip`이며 SHA-256은 다음과 같다.

```text
21872558c03619706ffc3882f5130459426bf6cdb1587e958d34654138748364
```

`adapter-out-external`의 계약 테스트는 Java record를 Jackson으로 직렬화한 결과가 고정
예시와 같고 JSON Schema를 통과하는지 확인한다. 이 검증은 BATON serializer의 요청 형식만
입증하며 신호 스트림, transactional outbox, 송신기와 BRIEF 종단 간 전달 완료를 뜻하지
않는다.

새 계약 버전을 적용할 때는 기존 디렉터리를 덮어쓰지 않고 새 버전 디렉터리를 추가한 뒤
생산자 모델·계약 테스트와 PRD-0006을 함께 갱신한다.
