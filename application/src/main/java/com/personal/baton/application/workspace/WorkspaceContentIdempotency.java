package com.personal.baton.application.workspace;

import org.springframework.stereotype.Component;
import com.personal.baton.application.crypto.DomainSeparatedSha256;
import com.personal.baton.application.workspace.error.IdempotencyKeyReusedException;
import com.personal.baton.application.workspace.port.out.WorkspaceAccessRepository;
import com.personal.baton.domain.workspace.ContentCreationIdempotency;
import com.personal.baton.domain.workspace.ContentCreationOperation;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

@Component
final class WorkspaceContentIdempotency {

    private static final String CONTENT_IDEMPOTENCY_HASH_DOMAIN =
            "baton:workspace-content-idempotency:v1";
    private static final String CONTENT_REQUEST_FINGERPRINT_DOMAIN =
            "baton:workspace-content-request:v1";

    private final WorkspaceAccessRepository repository;

    WorkspaceContentIdempotency(WorkspaceAccessRepository repository) {
        this.repository = repository;
    }

    // requestValues는 생성 요청을 구분하는 정규화 값이다. 작업 종류마다 같은 순서로 넘기고, 목록은 개수와 원소를 함께 지문에 담는다.
    ContentCreationAttempt prepare(
            UUID teamId,
            UUID seasonId,
            ContentCreationOperation operation,
            String idempotencyKey,
            UUID resourceId,
            Object... requestValues
    ) {
        String idempotencyHash = DomainSeparatedSha256.hashHex(
                CONTENT_IDEMPOTENCY_HASH_DOMAIN + ":" + operation.name(),
                List.of(teamId.toString(), seasonId.toString(), idempotencyKey)
        );
        String requestFingerprint = fingerprint(operation, teamId, seasonId, requestValues);
        ContentCreationIdempotency existing = repository.findContentCreationIdempotency(
                teamId,
                idempotencyHash
        ).orElse(null);
        if (existing != null) {
            validateReplay(existing, teamId, seasonId, operation, requestFingerprint);
            return new ContentCreationAttempt(operation, null, existing.getResourceId());
        }
        return new ContentCreationAttempt(operation, ContentCreationIdempotency.create(
                UUID.randomUUID(),
                teamId,
                seasonId,
                operation,
                idempotencyHash,
                requestFingerprint,
                resourceId
        ), null);
    }

    // 예약은 저장 직전 검증을 모두 통과한 뒤에 한다. 같은 키의 동시 요청은 이 시점의 유일 제약으로 갈린다.
    void reserve(ContentCreationAttempt attempt) {
        repository.saveContentCreationIdempotency(attempt.reservation());
    }

    private static String fingerprint(
            ContentCreationOperation operation,
            UUID teamId,
            UUID seasonId,
            Object... requestValues
    ) {
        DomainSeparatedSha256 digest = DomainSeparatedSha256
                .inDomain(CONTENT_REQUEST_FINGERPRINT_DOMAIN + ":" + operation.name())
                .append(teamId.toString())
                .append(seasonId.toString());
        for (Object value : requestValues) {
            if (value instanceof Collection<?> values) {
                digest.append(Integer.toString(values.size()));
                values.forEach(digest::appendNullable);
            } else {
                digest.appendNullable(value);
            }
        }
        return digest.digestHex();
    }

    private static void validateReplay(
            ContentCreationIdempotency existing,
            UUID teamId,
            UUID seasonId,
            ContentCreationOperation operation,
            String requestFingerprint
    ) {
        if (!existing.getTeamId().equals(teamId)
                || !existing.getSeasonId().equals(seasonId)
                || existing.getOperation() != operation) {
            throw new IllegalStateException(
                    "저장된 콘텐츠 생성 멱등 범위가 요청과 일치하지 않습니다"
            );
        }
        if (!existing.getRequestFingerprint().equals(requestFingerprint)) {
            throw new IdempotencyKeyReusedException();
        }
    }

    record ContentCreationAttempt(
            ContentCreationOperation operation,
            ContentCreationIdempotency reservation,
            UUID replayResourceId
    ) {

        // 같은 키의 재요청이면 처음 만든 리소스를 요청 범위에서 찾아 돌려준다. 찾지 못하면 저장 상태가 어긋난 것이다.
        <E> Optional<E> replay(Function<UUID, Optional<E>> findInScope) {
            if (replayResourceId == null) {
                return Optional.empty();
            }
            return Optional.of(findInScope.apply(replayResourceId).orElseThrow(() -> new IllegalStateException(
                    "멱등 생성된 " + operation.name() + " 리소스를 찾을 수 없습니다"
            )));
        }
    }
}
