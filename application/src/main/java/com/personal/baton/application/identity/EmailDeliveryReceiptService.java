package com.personal.baton.application.identity;

import com.personal.baton.application.identity.port.in.ReceiveEmailDeliveryReceiptUseCase;
import com.personal.baton.application.identity.port.out.EmailDeliveryReceiptPort;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailDeliveryReceiptService implements ReceiveEmailDeliveryReceiptUseCase {

    private final EmailDeliveryReceiptPort receiptPort;

    public EmailDeliveryReceiptService(EmailDeliveryReceiptPort receiptPort) {
        this.receiptPort = receiptPort;
    }

    @Override
    @Transactional
    public void receive(long deliveryId, EmailDeliveryEvent event, Instant occurredAt) {
        receiptPort.record(deliveryId, event, occurredAt);
    }
}
