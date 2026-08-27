package com.personal.baton.adapter.out.persistence.roundauth;

import com.personal.baton.domain.roundauth.RoundRoomMapping;
import com.personal.baton.domain.roundauth.RoundRoomTombstone;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RoundRoomMappingCreationTransaction {

    private final EntityManager entityManager;
    private final RoundRoomMappingJpaRepository mappingRepository;

    public RoundRoomMappingCreationTransaction(
            EntityManager entityManager,
            RoundRoomMappingJpaRepository mappingRepository
    ) {
        this.entityManager = entityManager;
        this.mappingRepository = mappingRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RoundRoomMapping create(
            RoundRoomTombstone tombstone,
            RoundRoomMapping mapping
    ) {
        try {
            entityManager.persist(tombstone);
            entityManager.flush();
        } catch (RuntimeException exception) {
            throw new TombstoneInsertException(exception);
        }
        try {
            entityManager.persist(mapping);
            entityManager.flush();
            return mapping;
        } catch (RuntimeException exception) {
            throw new MappingInsertException(exception);
        }
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            readOnly = true
    )
    public Optional<RoundRoomMapping> findByResourceId(UUID resourceId) {
        return mappingRepository.findByResourceId(resourceId);
    }

    static final class TombstoneInsertException extends RuntimeException {

        private final RuntimeException persistenceFailure;

        TombstoneInsertException(
                RuntimeException persistenceFailure
        ) {
            super(persistenceFailure);
            this.persistenceFailure = persistenceFailure;
        }

        RuntimeException persistenceFailure() {
            return persistenceFailure;
        }
    }

    static final class MappingInsertException extends RuntimeException {

        private final RuntimeException persistenceFailure;

        MappingInsertException(
                RuntimeException persistenceFailure
        ) {
            super(persistenceFailure);
            this.persistenceFailure = persistenceFailure;
        }

        RuntimeException persistenceFailure() {
            return persistenceFailure;
        }
    }
}
