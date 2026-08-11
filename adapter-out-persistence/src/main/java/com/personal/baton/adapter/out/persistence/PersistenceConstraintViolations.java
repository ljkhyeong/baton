package com.personal.baton.adapter.out.persistence;

import java.util.Locale;
import org.hibernate.exception.ConstraintViolationException;

public final class PersistenceConstraintViolations {

    private PersistenceConstraintViolations() {
    }

    public static boolean hasConstraint(Throwable throwable, String expectedName) {
        String normalizedExpectedName = expectedName.toLowerCase(Locale.ROOT);
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof ConstraintViolationException violation
                    && matches(violation.getConstraintName(), normalizedExpectedName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String actualName, String normalizedExpectedName) {
        if (actualName == null) {
            return false;
        }
        String normalizedActualName = actualName.replace("`", "").toLowerCase(Locale.ROOT);
        return normalizedActualName.equals(normalizedExpectedName)
                || normalizedActualName.endsWith("." + normalizedExpectedName);
    }
}
