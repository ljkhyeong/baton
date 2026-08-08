package com.personal.baton.bootstrap.config;

import com.personal.baton.adapter.out.external.relay.RabbitRelayEventPublisher;
import com.personal.baton.application.relay.RelayPublishResult;
import com.personal.baton.application.relay.port.out.RelayEventPublisher;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RelayPublisherProperties.class)
public class RelayPublisherConfig {

    @Bean
    @ConditionalOnBooleanProperty(prefix = "baton.relay.publisher", name = "enabled")
    RelayEventPublisher enabledRelayEventPublisher(
            RelayPublisherProperties properties,
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            ConnectionFactory connectionFactory
    ) {
        if (!(connectionFactory instanceof CachingConnectionFactory cachingConnectionFactory)
                || !cachingConnectionFactory.isSimplePublisherConfirms()
                || cachingConnectionFactory.isPublisherReturns()) {
            throw new IllegalStateException(
                    "RELAY publisher에는 simple confirms와 비활성 Spring publisher returns가 필요합니다"
            );
        }
        return new RabbitRelayEventPublisher(
                rabbitTemplate,
                objectMapper,
                properties.exchangeName(),
                properties.routingKey(),
                properties.confirmTimeout()
        );
    }

    @Bean
    @ConditionalOnBooleanProperty(
            prefix = "baton.relay.publisher",
            name = "enabled",
            havingValue = false,
            matchIfMissing = true
    )
    RelayEventPublisher disabledRelayEventPublisher() {
        return publication -> RelayPublishResult.retryable("PUBLISHER_DISABLED");
    }
}
