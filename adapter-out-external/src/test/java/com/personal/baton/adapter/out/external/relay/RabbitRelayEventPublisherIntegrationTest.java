package com.personal.baton.adapter.out.external.relay;

import static org.assertj.core.api.Assertions.assertThat;

import com.personal.baton.application.relay.RelayOutboxEvent;
import com.personal.baton.application.relay.RelayOutboxPublication;
import com.personal.baton.application.relay.RelayPublishResult;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers(disabledWithoutDocker = true)
class RabbitRelayEventPublisherIntegrationTest {

    private static final String EXCHANGE = "baton.events.publisher-test";
    private static final String QUEUE = "baton.relay.events.publisher-test";
    private static final String ROUTING_KEY = "relay.events.publisher-test";

    @Container
    private static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(
            "rabbitmq:4.3.4-management-alpine"
    );

    private static CachingConnectionFactory connectionFactory;
    private static RabbitTemplate rabbitTemplate;
    private static RabbitRelayEventPublisher publisher;

    @BeforeAll
    static void setUpBroker() {
        connectionFactory = new CachingConnectionFactory(
                RABBITMQ.getHost(),
                RABBITMQ.getAmqpPort()
        );
        connectionFactory.setUsername(RABBITMQ.getAdminUsername());
        connectionFactory.setPassword(RABBITMQ.getAdminPassword());
        connectionFactory.setPublisherConfirmType(
                CachingConnectionFactory.ConfirmType.SIMPLE
        );
        rabbitTemplate = new RabbitTemplate(connectionFactory);

        RabbitAdmin rabbitAdmin = new RabbitAdmin(connectionFactory);
        DirectExchange exchange = new DirectExchange(EXCHANGE, true, false);
        Queue queue = new Queue(QUEUE, true, false, false);
        rabbitAdmin.declareExchange(exchange);
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue).to(exchange).with(ROUTING_KEY));

        publisher = new RabbitRelayEventPublisher(
                rabbitTemplate,
                JsonMapper.builder().build(),
                EXCHANGE,
                ROUTING_KEY,
                Duration.ofSeconds(5)
        );
    }

    @AfterAll
    static void closeConnectionFactory() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @DisplayName("실제 RabbitMQ의 route와 positive confirm을 받은 persistent 사건만 성공한다")
    @Test
    void confirmsRoutedPersistentMessage() {
        RelayOutboxPublication publication = publication();

        RelayPublishResult result = publisher.publish(publication);

        assertThat(result).isEqualTo(RelayPublishResult.confirmed());
        Message received = rabbitTemplate.receive(QUEUE, 2_000);
        assertThat(received).isNotNull();
        assertThat(received.getMessageProperties().getReceivedDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(received.getMessageProperties().getMessageId())
                .isEqualTo(publication.event().eventId().toString());
        assertThat(received.getMessageProperties().getHeaders()).isEmpty();
        assertThat(received.getBody()).hasSizeLessThanOrEqualTo(2_048);
    }

    @DisplayName("실제 RabbitMQ가 unroutable mandatory 발행을 반환하면 positive confirm이어도 성공하지 않는다")
    @Test
    void retriesUnroutableMandatoryPublication() {
        RabbitRelayEventPublisher unroutablePublisher = new RabbitRelayEventPublisher(
                rabbitTemplate,
                JsonMapper.builder().build(),
                EXCHANGE,
                "relay.events.missing",
                Duration.ofSeconds(5)
        );

        RelayPublishResult result = unroutablePublisher.publish(publication());

        assertThat(result).isEqualTo(RelayPublishResult.retryable("MANDATORY_RETURN"));
    }

    private RelayOutboxPublication publication() {
        RelayOutboxEvent event = new RelayOutboxEvent(
                1,
                UUID.randomUUID(),
                "ROLE_HANDOFF_TRANSFERRED",
                1,
                "role:" + UUID.randomUUID(),
                Instant.parse("2026-08-08T01:02:03Z")
        );
        return new RelayOutboxPublication(1L, event, 1, UUID.randomUUID());
    }
}
