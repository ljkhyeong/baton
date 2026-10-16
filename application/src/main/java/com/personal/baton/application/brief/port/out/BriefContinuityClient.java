package com.personal.baton.application.brief.port.out;

import com.personal.baton.application.brief.BriefContinuityDelivery;
import com.personal.baton.application.delivery.DeliveryResult;

public interface BriefContinuityClient {

    DeliveryResult deliver(BriefContinuityDelivery delivery);
}
