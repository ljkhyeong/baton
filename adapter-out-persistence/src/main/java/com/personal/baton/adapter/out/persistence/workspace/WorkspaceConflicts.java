package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.application.workspace.error.WorkspaceContentConflictException;
import java.util.function.Supplier;
import org.springframework.dao.ConcurrencyFailureException;

// 낙관적·비관적 잠금 실패를 같은 콘텐츠 수정 충돌로 바꾼다.
final class WorkspaceConflicts {

    private WorkspaceConflicts() {
    }

    static <T> T translate(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (ConcurrencyFailureException exception) {
            throw new WorkspaceContentConflictException(exception);
        }
    }

    static void translate(Runnable operation) {
        translate(() -> {
            operation.run();
            return null;
        });
    }
}
