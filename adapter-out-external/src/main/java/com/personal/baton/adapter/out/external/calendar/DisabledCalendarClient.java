package com.personal.baton.adapter.out.external.calendar;

import com.personal.baton.application.calendar.CalendarSeasonMetadata;
import com.personal.baton.application.calendar.CalendarSnapshot;
import com.personal.baton.application.calendar.CalendarRecoveryManifest;
import com.personal.baton.application.calendar.port.out.CalendarRecoveryClient;
import com.personal.baton.application.calendar.port.out.CalendarSeasonMetadataClient;
import com.personal.baton.application.calendar.port.out.CalendarSnapshotClient;
import java.util.UUID;
import com.personal.baton.application.calendar.port.out.CalendarSubscriptionClient;

public final class DisabledCalendarClient implements
        CalendarSnapshotClient,
        CalendarSeasonMetadataClient,
        CalendarRecoveryClient,
        CalendarSubscriptionClient {

    @Override public Result create(UUID subscriptionId, UUID seasonId) { return Result.of(CalendarSubscriptionClient.Outcome.UNAVAILABLE); }
    @Override public Result findSubscription(UUID subscriptionId) { return Result.of(CalendarSubscriptionClient.Outcome.UNAVAILABLE); }
    @Override public Result rotate(UUID subscriptionId) { return Result.of(CalendarSubscriptionClient.Outcome.UNAVAILABLE); }
    @Override public Result revoke(UUID subscriptionId) { return Result.of(CalendarSubscriptionClient.Outcome.UNAVAILABLE); }

    @Override
    public java.util.Optional<RecoveryRun> findRecoveryRun(UUID recoveryId) { return java.util.Optional.empty(); }

    @Override
    public java.util.Optional<SeasonState> findRecoverySeason(UUID seasonId) { return java.util.Optional.empty(); }

    @Override
    public DeliveryResult deliver(CalendarSnapshot snapshot) {
        return DeliveryResult.retryable("CAL_DELIVERY_DISABLED");
    }

    @Override
    public DeliveryResult deliver(CalendarSeasonMetadata metadata) {
        return DeliveryResult.retryable("CAL_DELIVERY_DISABLED");
    }

    @Override
    public DeliveryResult verifySeason(UUID recoveryId, CalendarRecoveryManifest.Season season) {
        return DeliveryResult.retryable("CAL_DELIVERY_DISABLED");
    }

    @Override
    public DeliveryResult complete(UUID recoveryId, CalendarRecoveryManifest manifest) {
        return DeliveryResult.retryable("CAL_DELIVERY_DISABLED");
    }
}
