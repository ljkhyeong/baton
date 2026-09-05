package com.personal.baton.application.workspace.port.in;

import com.personal.baton.domain.workspace.ResourceVerificationStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ResourceVerificationUseCase {
    VerificationHistoryResult getHistory(UUID teamId, UUID seasonId, UUID resourceId, String accessKey);
    VerificationHistoryResult verify(UUID teamId, UUID seasonId, UUID resourceId, String accessKey,
            UUID accountId, VerifyResourceCommand command);

    record VerifyResourceCommand(long resourceVersion, ResourceVerificationStatus status, String note) {}
    record VerificationResult(UUID id, long resourceVersion, UUID memberId, String memberName,
            String url, ResourceVerificationStatus status, String note, Instant verifiedAt, boolean current) {}
    record VerificationHistoryResult(UUID teamId, UUID seasonId, UUID resourceId, long resourceVersion,
            List<VerificationResult> verifications) {}
}
