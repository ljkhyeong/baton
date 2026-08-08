package com.personal.baton.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.amqp.autoconfigure.health.RabbitHealthContributorAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RelayPublisherHealthConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(
                    RabbitAutoConfiguration.class,
                    RabbitHealthContributorAutoConfiguration.class
            ));

    @DisplayName("RELAY publisher가 기본 비활성이면 Rabbit 연결 bean은 있어도 health contributor는 만들지 않는다")
    @Test
    void disablesRabbitHealthContributorByDefault() {
        contextRunner.run(context -> {
            assertThat(context.getEnvironment().getProperty(
                    "management.health.rabbit.enabled",
                    Boolean.class
            )).isFalse();
            assertThat(context).hasSingleBean(RabbitTemplate.class);
            assertThat(context).doesNotHaveBean("rabbitHealthContributor");
        });
    }

    @DisplayName("Rabbit health 설정을 켜면 같은 auto-configuration이 contributor를 만든다")
    @Test
    void enablesRabbitHealthContributorWithExplicitConfiguration() {
        contextRunner
                .withPropertyValues("management.health.rabbit.enabled=true")
                .run(context -> assertThat(context).hasBean("rabbitHealthContributor"));
    }
}
