package com.personal.baton.application.identity.port.in;

import com.personal.baton.application.identity.EmailDeliveryEvent;
import java.time.Instant;

public interface ReceiveEmailDeliveryReceiptUseCase {

    void receive(long deliveryId, EmailDeliveryEvent event, Instant occurredAt);
}
