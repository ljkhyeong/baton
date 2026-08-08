package com.personal.baton.adapter.out.external.relay;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ReturnCallback;
import com.rabbitmq.client.ReturnListener;
import com.personal.baton.application.relay.RelayOutboxEvent;
import com.personal.baton.application.relay.RelayOutboxPublication;
import com.personal.baton.application.relay.RelayPublishResult;
import com.personal.baton.application.relay.port.out.RelayEventPublisher;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

public final class RabbitRelayEventPublisher implements RelayEventPublisher {

    static final int MAX_BODY_BYTES = 2_048;

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final String exchangeName;
    private final String routingKey;
    private final Duration confirmTimeout;

    public RabbitRelayEventPublisher(
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            String exchangeName,
            String routingKey,
            Duration confirmTimeout
    ) {
        this.rabbitTemplate = Objects.requireNonNull(
                rabbitTemplate,
                "RabbitTemplate은 필수입니다"
        );
        this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper는 필수입니다");
        this.exchangeName = requireText(exchangeName, "exchange name");
        this.routingKey = requireText(routingKey, "routing key");
        this.confirmTimeout = requirePositive(confirmTimeout);
    }

    @Override
    public RelayPublishResult publish(RelayOutboxPublication publication) {
        RelayOutboxEvent event = Objects.requireNonNull(
                publication,
                "RELAY publication은 필수입니다"
        ).event();
        byte[] body = body(event);
        try {
            return rabbitTemplate.execute(channel -> publishOnChannel(channel, event, body));
        } catch (AmqpException exception) {
            return RelayPublishResult.retryable("PUBLISH_FAILED");
        }
    }

    @SuppressWarnings("deprecation")
    private RelayPublishResult publishOnChannel(
            Channel channel,
            RelayOutboxEvent event,
            byte[] body
    ) throws Exception {
        AtomicBoolean returned = new AtomicBoolean();
        ReturnListener returnListener = channel.addReturnListener(
                (ReturnCallback) returnedMessage -> returned.set(true)
        );
        try {
            channel.confirmSelect();
            channel.basicPublish(
                    exchangeName,
                    routingKey,
                    true,
                    new AMQP.BasicProperties.Builder()
                            .contentType("application/json")
                            .deliveryMode(2)
                            .messageId(event.eventId().toString())
                            .build(),
                    body
            );
            boolean acknowledged;
            try {
                acknowledged = channel.waitForConfirms(confirmTimeout.toMillis());
            } catch (TimeoutException exception) {
                return RelayPublishResult.retryable("CONFIRM_TIMEOUT");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return RelayPublishResult.retryable("PUBLISH_INTERRUPTED");
            }
            if (!acknowledged) {
                return RelayPublishResult.retryable("PUBLISH_NACK");
            }
            return returned.get()
                    ? RelayPublishResult.retryable("MANDATORY_RETURN")
                    : RelayPublishResult.confirmed();
        } finally {
            channel.removeReturnListener(returnListener);
        }
    }

    private byte[] body(RelayOutboxEvent event) {
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(new RelayWireEnvelope(
                    event.contractVersion(),
                    event.eventId().toString(),
                    event.eventType(),
                    event.eventVersion(),
                    event.subjectReference(),
                    event.occurredAt().toString()
            ));
        } catch (JacksonException exception) {
            throw new IllegalStateException("RELAY event를 JSON으로 직렬화할 수 없습니다", exception);
        }
        if (body.length > MAX_BODY_BYTES) {
            throw new IllegalStateException("RELAY event JSON은 2048 bytes 이하여야 합니다");
        }

        return body;
    }

    private String requireText(String value, String name) {
        String requiredValue = Objects.requireNonNull(value, "RabbitMQ " + name + "은 필수입니다");
        if (requiredValue.isBlank()) {
            throw new IllegalArgumentException("RabbitMQ " + name + "은 비어 있을 수 없습니다");
        }
        return requiredValue;
    }

    private Duration requirePositive(Duration value) {
        Duration requiredValue = Objects.requireNonNull(value, "confirm timeout은 필수입니다");
        if (requiredValue.isZero() || requiredValue.isNegative()) {
            throw new IllegalArgumentException("confirm timeout은 0보다 커야 합니다");
        }
        return requiredValue;
    }

    private record RelayWireEnvelope(
            @JsonProperty("contractVersion") int contractVersion,
            @JsonProperty("eventId") String eventId,
            @JsonProperty("eventType") String eventType,
            @JsonProperty("eventVersion") int eventVersion,
            @JsonProperty("subjectReference") String subjectReference,
            @JsonProperty("occurredAt") String occurredAt
    ) {
    }
}
