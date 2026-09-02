package com.personal.baton.application.calendar.port.out;

import com.personal.baton.application.calendar.CalendarRecoveryManifest;
import com.personal.baton.application.calendar.port.out.CalendarSnapshotClient.DeliveryResult;
import java.util.UUID;

public interface CalendarRecoveryClient {

    DeliveryResult verifySeason(UUID recoveryId, CalendarRecoveryManifest.Season season);

    DeliveryResult complete(UUID recoveryId, CalendarRecoveryManifest manifest);
}
