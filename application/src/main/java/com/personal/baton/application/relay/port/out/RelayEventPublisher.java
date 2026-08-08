package com.personal.baton.application.relay.port.out;

import com.personal.baton.application.relay.RelayOutboxPublication;
import com.personal.baton.application.relay.RelayPublishResult;

public interface RelayEventPublisher {

    RelayPublishResult publish(RelayOutboxPublication publication);
}
