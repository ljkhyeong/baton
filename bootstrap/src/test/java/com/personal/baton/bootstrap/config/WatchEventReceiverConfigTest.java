package com.personal.baton.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.personal.baton.adapter.in.web.config.WatchEventReceiverAuthentication;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class WatchEventReceiverConfigTest {

    private static final String RECEIVER_TOKEN =
            "receiver-token-with-at-least-32-characters";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(WatchEventReceiverConfig.class);

    @DisplayName("WATCH 이벤트 수신은 기본 비활성 상태에서 token 없이 시작한다")
    @Test
    void startDisabledWithoutCredentials() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(WatchEventReceiverAuthentication.class);
            assertThat(context.getBean(WatchEventReceiverAuthentication.class).toString())
                    .contains("enabled=false")
                    .contains("bearerToken=<redacted>");
        });
    }

    @DisplayName("WATCH 이벤트 수신을 켜면 별도 token과 source namespace로 인증 경계를 조립한다")
    @Test
    void createEnabledReceiver() {
        contextRunner
                .withPropertyValues(
                        "baton.watch.source-namespace=study-pilot",
                        "baton.watch.event-receiver.enabled=true",
                        "baton.watch.event-receiver.bearer-token=" + RECEIVER_TOKEN
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(WatchEventReceiverAuthentication.class).toString())
                            .contains("enabled=true")
                            .doesNotContain(RECEIVER_TOKEN);
                });
    }

    @DisplayName("WATCH 이벤트 수신을 켤 때 source namespace가 비어 있으면 시작을 거부한다")
    @Test
    void rejectEnabledReceiverWithoutSourceNamespace() {
        contextRunner
                .withPropertyValues(
                        "baton.watch.event-receiver.enabled=true",
                        "baton.watch.event-receiver.bearer-token=" + RECEIVER_TOKEN
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "WATCH 이벤트 수신을 켤 때 source namespace는 필수입니다"
                    );
                });
    }

    @DisplayName("WATCH 이벤트 수신을 켤 때 token이 짧으면 시작을 거부한다")
    @Test
    void rejectEnabledReceiverWithShortToken() {
        contextRunner
                .withPropertyValues(
                        "baton.watch.source-namespace=study-pilot",
                        "baton.watch.event-receiver.enabled=true",
                        "baton.watch.event-receiver.bearer-token=too-short"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "WATCH 이벤트 수신 bearer token은 32~200자의 URL-safe ASCII여야 합니다"
                    );
                });
    }

    @DisplayName("WATCH 이벤트 수신 token과 outbound WATCH token이 같으면 시작을 거부한다")
    @Test
    void rejectReusedOutboundToken() {
        contextRunner
                .withPropertyValues(
                        "baton.watch.source-namespace=study-pilot",
                        "baton.watch.bearer-token=" + RECEIVER_TOKEN,
                        "baton.watch.event-receiver.enabled=true",
                        "baton.watch.event-receiver.bearer-token=" + RECEIVER_TOKEN
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "WATCH 이벤트 수신 token과 outbound WATCH token은 달라야 합니다"
                    );
                });
    }

    @DisplayName("WATCH 이벤트 수신 token과 outbound WATCH token이 다르면 함께 설정할 수 있다")
    @Test
    void acceptDistinctOutboundToken() {
        contextRunner
                .withPropertyValues(
                        "baton.watch.source-namespace=study-pilot",
                        "baton.watch.bearer-token=outbound-token-with-at-least-32-characters",
                        "baton.watch.event-receiver.enabled=true",
                        "baton.watch.event-receiver.bearer-token=" + RECEIVER_TOKEN
                )
                .run(context -> assertThat(context).hasNotFailed());
    }

    @DisplayName("WATCH 이벤트 수신 token을 워크스페이스 운영 키로 재사용하면 시작을 거부한다")
    @Test
    void rejectReusedWorkspaceToken() {
        for (String propertyName : List.of(
                "baton.workspace.creation-key",
                "baton.workspace.recovery-key"
        )) {
            contextRunner
                    .withPropertyValues(
                            "baton.watch.source-namespace=study-pilot",
                            "baton.watch.event-receiver.enabled=true",
                            "baton.watch.event-receiver.bearer-token=" + RECEIVER_TOKEN,
                            propertyName + "=" + RECEIVER_TOKEN
                    )
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure()).hasRootCauseMessage(
                                "WATCH 이벤트 수신 token과 워크스페이스 운영 키는 달라야 합니다"
                        );
                    });
        }
    }

    @DisplayName("WATCH 이벤트 수신 설정 문자열은 bearer token을 노출하지 않는다")
    @Test
    void redactTokenFromPropertiesString() {
        WatchEventReceiverProperties properties = new WatchEventReceiverProperties(
                true,
                RECEIVER_TOKEN
        );

        assertThat(properties.toString())
                .doesNotContain(RECEIVER_TOKEN)
                .contains("bearerToken=<redacted>");
    }
}
