package com.personal.baton.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.baton.adapter.out.external.relay.RabbitRelayEventPublisher;
import com.personal.baton.application.relay.RelayOutboxEvent;
import com.personal.baton.application.relay.RelayOutboxPublication;
import com.personal.baton.application.relay.RelayPublishResult;
import com.personal.baton.application.relay.port.out.RelayEventPublisher;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.json.JsonMapper;

class RelayPublisherConfigTest {

    private final RelayPublisherConfig config = new RelayPublisherConfig();

    @DisplayName("RELAY publisher는 기본 비활성일 때 broker를 호출하지 않는 구현을 조립한다")
    @Test
    void createsDisabledPublisherWithoutBrokerConfiguration() {
        RelayEventPublisher publisher = config.disabledRelayEventPublisher();

        RelayPublishResult result = publisher.publish(publication());

        assertThat(result).isEqualTo(RelayPublishResult.retryable("PUBLISHER_DISABLED"));
    }

    @DisplayName("RELAY publisher를 켜면 channel 단위 simple confirm과 raw return 경계를 강제한다")
    @Test
    void createsEnabledPublisherWithRequiredRabbitGuarantees() {
        CachingConnectionFactory connectionFactory = new CachingConnectionFactory("localhost");
        connectionFactory.setPublisherConfirmType(
                CachingConnectionFactory.ConfirmType.SIMPLE
        );
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);

        RelayEventPublisher publisher = config.enabledRelayEventPublisher(
                properties(true),
                rabbitTemplate,
                JsonMapper.builder().build(),
                connectionFactory
        );

        assertThat(publisher).isInstanceOf(RabbitRelayEventPublisher.class);
        assertThat(connectionFactory.isSimplePublisherConfirms()).isTrue();
        assertThat(connectionFactory.isPublisherReturns()).isFalse();
        connectionFactory.destroy();
    }

    @DisplayName("correlated callback channel을 사용하는 Rabbit 연결은 시작을 거부한다")
    @Test
    void rejectsRabbitConnectionWithoutRequiredGuarantees() {
        CachingConnectionFactory connectionFactory = new CachingConnectionFactory("localhost");
        connectionFactory.setPublisherConfirmType(
                CachingConnectionFactory.ConfirmType.CORRELATED
        );
        connectionFactory.setPublisherReturns(true);
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);

        assertThatThrownBy(() -> config.enabledRelayEventPublisher(
                properties(true),
                rabbitTemplate,
                JsonMapper.builder().build(),
                connectionFactory
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simple confirms");
        connectionFactory.destroy();
    }

    @DisplayName("Rabbit 이름과 confirm 시간 예산이 계약을 벗어나면 설정을 거부한다")
    @Test
    void rejectsInvalidPublisherProperties() {
        assertThatThrownBy(() -> new RelayPublisherProperties(
                true,
                "rabbit.internal",
                5672,
                "baton-user",
                "relay-secret",
                "/baton",
                "invalid exchange",
                "relay.events.v1",
                Duration.ofSeconds(5),
                Duration.ofSeconds(10)
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new RelayPublisherProperties(
                true,
                "rabbit.internal",
                5672,
                "baton-user",
                "relay-secret",
                "/baton",
                "baton.events.v1",
                "relay.events.v1",
                Duration.ofSeconds(46),
                Duration.ofSeconds(10)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("45초 이하");
    }

    @DisplayName("RELAY publisher를 켤 때 Rabbit 연결값이 빠지면 시작을 거부한다")
    @Test
    void rejectsMissingBrokerConfigurationWhenEnabled() {
        assertThatThrownBy(() -> new RelayPublisherProperties(
                true,
                null,
                0,
                null,
                null,
                null,
                "baton.events.v1",
                "relay.events.v1",
                Duration.ofSeconds(5),
                Duration.ofSeconds(10)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RabbitMQ host은 필수");
    }

    @DisplayName("RELAY publisher 설정 문자열은 broker 위치와 자격 정보를 노출하지 않는다")
    @Test
    void redactsBrokerConfigurationFromToString() {
        RelayPublisherProperties properties = properties(true);

        assertThat(properties.toString())
                .doesNotContain("rabbit.internal", "baton-user", "relay-secret", "/baton")
                .contains("brokerHost=<redacted>")
                .contains("brokerUsername=<redacted>")
                .contains("brokerPassword=<redacted>")
                .contains("brokerVirtualHost=<redacted>");
    }

    private RelayPublisherProperties properties(boolean enabled) {
        return new RelayPublisherProperties(
                enabled,
                "rabbit.internal",
                5672,
                "baton-user",
                "relay-secret",
                "/baton",
                "baton.events.v1",
                "relay.events.v1",
                Duration.ofSeconds(5),
                Duration.ofSeconds(10)
        );
    }

    private RelayOutboxPublication publication() {
        return new RelayOutboxPublication(
                1L,
                new RelayOutboxEvent(
                        1,
                        UUID.randomUUID(),
                        "ROLE_HANDOFF_TRANSFERRED",
                        1,
                        "role:" + UUID.randomUUID(),
                        Instant.parse("2026-08-08T01:02:03Z")
                ),
                1,
                UUID.randomUUID()
        );
    }
}
