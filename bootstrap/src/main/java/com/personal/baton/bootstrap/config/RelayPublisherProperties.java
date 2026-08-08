package com.personal.baton.bootstrap.config;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("baton.relay.publisher")
record RelayPublisherProperties(
        boolean enabled,
        String brokerHost,
        Integer brokerPort,
        String brokerUsername,
        String brokerPassword,
        String brokerVirtualHost,
        String exchangeName,
        String routingKey,
        Duration confirmTimeout,
        Duration dispatchInterval
) {

    private static final String DEFAULT_EXCHANGE_NAME = "baton.events.v1";
    private static final String DEFAULT_ROUTING_KEY = "relay.events.v1";
    private static final Duration DEFAULT_CONFIRM_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_DISPATCH_INTERVAL = Duration.ofSeconds(10);
    private static final Duration MAX_CONFIRM_TIMEOUT = Duration.ofSeconds(45);
    private static final Pattern BROKER_NAME_PATTERN = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$"
    );

    RelayPublisherProperties {
        brokerHost = Objects.requireNonNullElse(brokerHost, "");
        brokerPort = Objects.requireNonNullElse(brokerPort, 0);
        brokerUsername = Objects.requireNonNullElse(brokerUsername, "");
        brokerPassword = Objects.requireNonNullElse(brokerPassword, "");
        brokerVirtualHost = Objects.requireNonNullElse(brokerVirtualHost, "");
        if (enabled) {
            requireBrokerConnection(
                    brokerHost,
                    brokerPort,
                    brokerUsername,
                    brokerPassword,
                    brokerVirtualHost
            );
        }
        exchangeName = requireBrokerName(
                Objects.requireNonNullElse(exchangeName, DEFAULT_EXCHANGE_NAME),
                "exchange name"
        );
        routingKey = requireBrokerName(
                Objects.requireNonNullElse(routingKey, DEFAULT_ROUTING_KEY),
                "routing key"
        );
        confirmTimeout = requirePositive(
                Objects.requireNonNullElse(confirmTimeout, DEFAULT_CONFIRM_TIMEOUT),
                "confirm timeout"
        );
        if (confirmTimeout.compareTo(MAX_CONFIRM_TIMEOUT) > 0) {
            throw new IllegalStateException("RELAY confirm timeout은 45초 이하여야 합니다");
        }
        dispatchInterval = requirePositive(
                Objects.requireNonNullElse(dispatchInterval, DEFAULT_DISPATCH_INTERVAL),
                "dispatch interval"
        );
    }

    private static void requireBrokerConnection(
            String host,
            int port,
            String username,
            String password,
            String virtualHost
    ) {
        requireConfigured(host, "host");
        if (port < 1 || port > 65_535) {
            throw new IllegalStateException(
                    "RELAY RabbitMQ port는 1부터 65535 사이여야 합니다"
            );
        }
        requireConfigured(username, "username");
        requireConfigured(password, "password");
        requireConfigured(virtualHost, "virtual host");
    }

    private static void requireConfigured(String value, String name) {
        if (value.isBlank()) {
            throw new IllegalStateException(
                    "RELAY publisher를 켤 때 RabbitMQ " + name + "은 필수입니다"
            );
        }
    }

    private static String requireBrokerName(String value, String name) {
        if (!BROKER_NAME_PATTERN.matcher(value).matches()) {
            throw new IllegalStateException("RELAY RabbitMQ " + name + " 형식이 올바르지 않습니다");
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value.compareTo(Duration.ofMillis(1)) < 0) {
            throw new IllegalStateException("RELAY " + name + "은 1ms 이상이어야 합니다");
        }
        return value;
    }

    @Override
    public String toString() {
        return "RelayPublisherProperties[enabled=" + enabled
                + ", brokerHost=<redacted>, brokerPort=" + brokerPort
                + ", brokerUsername=<redacted>, brokerPassword=<redacted>"
                + ", brokerVirtualHost=<redacted>, exchangeName=" + exchangeName
                + ", routingKey=" + routingKey + ", confirmTimeout=" + confirmTimeout
                + ", dispatchInterval=" + dispatchInterval + "]";
    }
}
