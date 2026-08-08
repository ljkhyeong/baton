package com.personal.baton.adapter.out.external.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Return;
import com.rabbitmq.client.ReturnCallback;
import com.rabbitmq.client.ReturnListener;
import com.personal.baton.application.relay.RelayOutboxEvent;
import com.personal.baton.application.relay.RelayOutboxPublication;
import com.personal.baton.application.relay.RelayPublishResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

@SuppressWarnings({"deprecation", "unchecked"})
class RabbitRelayEventPublisherTest {

    private static final String EXCHANGE = "baton.events.v1";
    private static final String ROUTING_KEY = "relay.events.v1";
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-08T01:02:03.123456Z");
    private static final UUID EVENT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000001721"
    );
    private static final UUID ROLE_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000001722"
    );
    private static final UUID LEASE_TOKEN = UUID.fromString(
            "00000000-0000-0000-0000-000000001723"
    );

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final Channel channel = mock(Channel.class);
    private final ReturnListener registeredReturnListener = mock(ReturnListener.class);
    private final AtomicReference<ReturnCallback> returnCallback = new AtomicReference<>();
    private final JsonMapper objectMapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    @BeforeEach
    void setUp() {
        doAnswer(invocation -> {
            returnCallback.set(invocation.getArgument(0));
            return registeredReturnListener;
        }).when(channel).addReturnListener(any(ReturnCallback.class));
        doAnswer(invocation -> {
            ChannelCallback<?> callback = invocation.getArgument(0);
            try {
                return callback.doInRabbit(channel);
            } catch (Exception exception) {
                throw new AmqpException("Rabbit channel callback이 실패했습니다", exception);
            }
        }).when(rabbitTemplate).execute(any(ChannelCallback.class));
    }

    @DisplayName("전역 JSON 이름 전략과 무관하게 정확한 여섯 필드와 허용된 AMQP 속성만 mandatory로 보낸다")
    @Test
    void publishesExactEnvelopeAndProperties() throws Exception {
        RelayOutboxPublication publication = publication();
        when(channel.waitForConfirms(1_000L)).thenReturn(true);
        RabbitRelayEventPublisher publisher = publisher(Duration.ofSeconds(1));

        RelayPublishResult result = publisher.publish(publication);

        assertThat(result).isEqualTo(RelayPublishResult.confirmed());
        ArgumentCaptor<AMQP.BasicProperties> propertiesCaptor =
                ArgumentCaptor.forClass(AMQP.BasicProperties.class);
        ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(channel).confirmSelect();
        verify(channel).basicPublish(
                eq(EXCHANGE),
                eq(ROUTING_KEY),
                eq(true),
                propertiesCaptor.capture(),
                bodyCaptor.capture()
        );
        verify(channel).waitForConfirms(1_000L);
        verify(channel).removeReturnListener(registeredReturnListener);

        AMQP.BasicProperties properties = propertiesCaptor.getValue();
        assertThat(properties.getContentType()).isEqualTo("application/json");
        assertThat(properties.getDeliveryMode()).isEqualTo(2);
        assertThat(properties.getMessageId()).isEqualTo(EVENT_ID.toString());
        assertThat(properties.getContentEncoding()).isNull();
        assertThat(properties.getHeaders()).isNull();
        assertThat(properties.getPriority()).isNull();
        assertThat(properties.getCorrelationId()).isNull();
        assertThat(properties.getReplyTo()).isNull();
        assertThat(properties.getExpiration()).isNull();
        assertThat(properties.getTimestamp()).isNull();
        assertThat(properties.getType()).isNull();
        assertThat(properties.getUserId()).isNull();
        assertThat(properties.getAppId()).isNull();
        assertThat(properties.getClusterId()).isNull();

        byte[] body = bodyCaptor.getValue();
        JsonNode root = objectMapper.readTree(body);
        assertThat(root.propertyNames()).containsExactlyInAnyOrder(
                "contractVersion",
                "eventId",
                "eventType",
                "eventVersion",
                "subjectReference",
                "occurredAt"
        );
        assertThat(root.get("contractVersion").intValue()).isEqualTo(1);
        assertThat(root.get("eventId").textValue()).isEqualTo(EVENT_ID.toString());
        assertThat(root.get("eventType").textValue()).isEqualTo("ROLE_HANDOFF_TRANSFERRED");
        assertThat(root.get("eventVersion").intValue()).isEqualTo(1);
        assertThat(root.get("subjectReference").textValue()).isEqualTo("role:" + ROLE_ID);
        assertThat(root.get("occurredAt").textValue()).isEqualTo(OCCURRED_AT.toString());
        assertThat(body).hasSizeLessThanOrEqualTo(2_048);
        assertThat(new String(body, StandardCharsets.UTF_8))
                .doesNotContain("headers", "payload");
    }

    @DisplayName("positive confirm이 와도 mandatory return이 있으면 발행 완료로 보지 않는다")
    @Test
    void retriesPositiveConfirmWithMandatoryReturn() throws Exception {
        when(channel.waitForConfirms(1_000L)).thenAnswer(invocation -> {
            returnCallback.get().handle(new Return(
                    312,
                    "NO_ROUTE",
                    EXCHANGE,
                    ROUTING_KEY,
                    new AMQP.BasicProperties(),
                    new byte[0]
            ));
            return true;
        });

        RelayPublishResult result = publisher(Duration.ofSeconds(1)).publish(publication());

        assertThat(result).isEqualTo(RelayPublishResult.retryable("MANDATORY_RETURN"));
        verify(channel).removeReturnListener(registeredReturnListener);
    }

    @DisplayName("publisher nack은 같은 outbox 사건을 다시 시도할 결과로 분류한다")
    @Test
    void retriesPublisherNack() throws Exception {
        when(channel.waitForConfirms(1_000L)).thenReturn(false);

        RelayPublishResult result = publisher(Duration.ofSeconds(1)).publish(publication());

        assertThat(result).isEqualTo(RelayPublishResult.retryable("PUBLISH_NACK"));
        verify(channel).removeReturnListener(registeredReturnListener);
    }

    @DisplayName("confirm 제한 시간 안에 결과가 없으면 발행 결과를 미확정으로 남긴다")
    @Test
    void retriesConfirmTimeout() throws Exception {
        when(channel.waitForConfirms(1L)).thenThrow(new TimeoutException("confirm timeout"));

        RelayPublishResult result = publisher(Duration.ofMillis(1)).publish(publication());

        assertThat(result).isEqualTo(RelayPublishResult.retryable("CONFIRM_TIMEOUT"));
        verify(channel).removeReturnListener(registeredReturnListener);
    }

    @DisplayName("confirm 대기가 중단되면 interrupt 상태를 복원하고 같은 사건을 다시 시도한다")
    @Test
    void retriesInterruptedConfirmAndRestoresInterrupt() throws Exception {
        when(channel.waitForConfirms(1_000L)).thenThrow(new InterruptedException("interrupted"));
        Thread.interrupted();

        try {
            RelayPublishResult result = publisher(Duration.ofSeconds(1)).publish(publication());

            assertThat(result).isEqualTo(RelayPublishResult.retryable("PUBLISH_INTERRUPTED"));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(channel).removeReturnListener(registeredReturnListener);
        } finally {
            Thread.interrupted();
        }
    }

    @DisplayName("channel 발행 준비가 실패해도 return listener를 제거하고 재시도 결과를 남긴다")
    @Test
    void removesReturnListenerWhenChannelOperationFails() throws Exception {
        when(channel.confirmSelect()).thenThrow(new IOException("channel closed"));

        RelayPublishResult result = publisher(Duration.ofSeconds(1)).publish(publication());

        assertThat(result).isEqualTo(RelayPublishResult.retryable("PUBLISH_FAILED"));
        verify(channel).removeReturnListener(registeredReturnListener);
    }

    private RabbitRelayEventPublisher publisher(Duration timeout) {
        return new RabbitRelayEventPublisher(
                rabbitTemplate,
                objectMapper,
                EXCHANGE,
                ROUTING_KEY,
                timeout
        );
    }

    private RelayOutboxPublication publication() {
        RelayOutboxEvent event = new RelayOutboxEvent(
                1,
                EVENT_ID,
                "ROLE_HANDOFF_TRANSFERRED",
                1,
                "role:" + ROLE_ID,
                OCCURRED_AT
        );
        return new RelayOutboxPublication(1L, event, 1, LEASE_TOKEN);
    }
}
