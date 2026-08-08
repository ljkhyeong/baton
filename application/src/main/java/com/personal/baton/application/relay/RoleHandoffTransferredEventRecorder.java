package com.personal.baton.application.relay;

import com.personal.baton.application.relay.port.out.RelayOutboxPort;
import com.personal.baton.domain.workspace.RoleHandoff;
import com.personal.baton.domain.workspace.RoleHandoffStatus;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RoleHandoffTransferredEventRecorder {

    public static final String EVENT_TYPE = "ROLE_HANDOFF_TRANSFERRED";
    public static final int EVENT_VERSION = 1;

    private final RelayOutboxPort outboxPort;

    public RoleHandoffTransferredEventRecorder(RelayOutboxPort outboxPort) {
        this.outboxPort = outboxPort;
    }

    public void record(RoleHandoff handoff) {
        RoleHandoff requiredHandoff = Objects.requireNonNull(
                handoff,
                "전달된 역할 바통은 필수입니다"
        );
        if (requiredHandoff.getStatus() != RoleHandoffStatus.TRANSFERRED
                || requiredHandoff.getTransferredAt() == null) {
            throw new IllegalArgumentException("전달 완료 시점의 역할 바통만 RELAY에 기록할 수 있습니다");
        }
        outboxPort.append(new RelayOutboxEvent(
                RelayOutboxEvent.CURRENT_CONTRACT_VERSION,
                UUID.randomUUID(),
                EVENT_TYPE,
                EVENT_VERSION,
                "role:" + requiredHandoff.getRoleId(),
                requiredHandoff.getTransferredAt()
        ));
    }
}
