package com.personal.baton.application.delivery;

import java.util.List;
import java.util.function.Predicate;

public record DispatchResult(int claimedCount, int deliveredCount, int failedCount) {

    // 선점한 항목을 하나씩 전달하고, 전달하지 못한 항목은 실패로 센다.
    public static <T> DispatchResult dispatchEach(List<T> claimed, Predicate<T> dispatchOne) {
        int deliveredCount = 0;
        for (T item : claimed) {
            if (dispatchOne.test(item)) {
                deliveredCount++;
            }
        }
        return new DispatchResult(claimed.size(), deliveredCount, claimed.size() - deliveredCount);
    }

    public DispatchResult plus(DispatchResult other) {
        return new DispatchResult(
                claimedCount + other.claimedCount,
                deliveredCount + other.deliveredCount,
                failedCount + other.failedCount
        );
    }

    public boolean hasFailures() {
        return failedCount > 0;
    }
}
