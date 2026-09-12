package com.personal.baton.application.identity.port.out;

import com.personal.baton.application.identity.EmailDeliveryEvent;
import java.time.Instant;

public interface EmailDeliveryReceiptPort {

    void record(long deliveryId, EmailDeliveryEvent event, Instant occurredAt);
}
