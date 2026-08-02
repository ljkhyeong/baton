package com.personal.baton.bootstrap.config;

import com.personal.baton.adapter.in.web.config.WatchEventReceiverAuthentication;
import com.personal.baton.application.watch.WatchMonitorSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        WatchEventReceiverProperties.class,
        WatchIntegrationProperties.class,
        WorkspaceSecretProperties.class
})
public class WatchEventReceiverConfig {

    @Bean
    WatchEventReceiverAuthentication watchEventReceiverAuthentication(
            WatchEventReceiverProperties receiverProperties,
            WatchIntegrationProperties watchProperties,
            WorkspaceSecretProperties workspaceProperties
    ) {
        if (!receiverProperties.enabled()) {
            return WatchEventReceiverAuthentication.disabled();
        }

        requireSourceNamespace(watchProperties.sourceNamespace());
        String receiverToken = receiverProperties.requiredBearerToken();
        requireDistinctTokens(receiverToken, watchProperties.bearerToken());
        requireDistinctWorkspaceTokens(receiverToken, workspaceProperties);
        return WatchEventReceiverAuthentication.enabled(receiverToken);
    }

    static void requireSourceNamespace(String sourceNamespace) {
        if (sourceNamespace == null || sourceNamespace.isBlank()) {
            throw new IllegalStateException(
                    "WATCH 이벤트 수신을 켤 때 source namespace는 필수입니다"
            );
        }
        new WatchMonitorSource(sourceNamespace);
    }

    static void requireDistinctTokens(String receiverToken, String outboundToken) {
        requireDistinctToken(
                receiverToken,
                outboundToken,
                "WATCH 이벤트 수신 token과 outbound WATCH token은 달라야 합니다"
        );
    }

    static void requireDistinctWorkspaceTokens(
            String receiverToken,
            WorkspaceSecretProperties workspaceProperties
    ) {
        requireDistinctToken(
                receiverToken,
                workspaceProperties.creationKey(),
                "WATCH 이벤트 수신 token과 워크스페이스 운영 키는 달라야 합니다"
        );
        requireDistinctToken(
                receiverToken,
                workspaceProperties.recoveryKey(),
                "WATCH 이벤트 수신 token과 워크스페이스 운영 키는 달라야 합니다"
        );
    }

    private static void requireDistinctToken(
            String receiverToken,
            String otherToken,
            String message
    ) {
        if (otherToken == null || otherToken.isBlank()) {
            return;
        }
        boolean equal = MessageDigest.isEqual(
                receiverToken.getBytes(StandardCharsets.UTF_8),
                otherToken.getBytes(StandardCharsets.UTF_8)
        );
        if (equal) {
            throw new IllegalStateException(message);
        }
    }
}
