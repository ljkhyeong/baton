package com.personal.baton.adapter.in.web.auth;

import com.personal.baton.application.identity.error.IdentityOperationUnavailableException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;

final class IdentityInfrastructureFailures {

    private IdentityInfrastructureFailures() {
    }

    static Optional<RuntimeException> find(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && visited.add(current)) {
            if (isUnavailable(current)) {
                return Optional.of((RuntimeException) current);
            }
            current = current.getCause();
        }
        return Optional.empty();
    }

    private static boolean isUnavailable(Throwable failure) {
        return failure instanceof IdentityOperationUnavailableException
                || failure instanceof CannotCreateTransactionException
                || failure instanceof TransactionTimedOutException
                || failure instanceof TransientDataAccessException
                || failure instanceof RecoverableDataAccessException
                || failure instanceof DataAccessResourceFailureException;
    }
}
