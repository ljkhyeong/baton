package com.personal.baton.application.brief;

import java.time.Instant;
import java.util.UUID;

/** 추가 전달 여부는 BATON 기록, 내용 일치 여부는 BRIEF 최신 여부 확인에서 온다. */
public record BriefEditionDeliveryStatus(
        UUID editionId, Status status, boolean upToDate, boolean ruleVersionChanged, Instant checkedAt
) {
    public enum Status {
        ADDITIONAL_DELIVERIES,
        NO_ADDITIONAL_DELIVERIES,
        UNKNOWN
    }
}
