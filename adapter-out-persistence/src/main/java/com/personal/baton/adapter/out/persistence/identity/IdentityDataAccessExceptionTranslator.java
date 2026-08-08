package com.personal.baton.adapter.out.persistence.identity;

import com.personal.baton.application.identity.error.IdentityOperationUnavailableException;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;

final class IdentityDataAccessExceptionTranslator {

    private IdentityDataAccessExceptionTranslator() {
    }

    static <T> T translateTemporaryFailure(String message, Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessException exception) {
            if (isTemporarilyUnavailable(exception)) {
                throw new IdentityOperationUnavailableException(message, exception);
            }
            throw exception;
        }
    }

    static void translateTemporaryFailure(String message, Runnable operation) {
        translateTemporaryFailure(message, () -> {
            operation.run();
            return null;
        });
    }

    private static boolean isTemporarilyUnavailable(DataAccessException exception) {
        return exception instanceof TransientDataAccessException
                || exception instanceof RecoverableDataAccessException
                || exception instanceof DataAccessResourceFailureException;
    }
}
