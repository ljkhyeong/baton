package com.personal.baton.bootstrap.config;

import com.personal.baton.adapter.out.external.link.BatonGoProperties;
import com.personal.baton.adapter.out.external.link.BatonGoRoleResourceLinkAdapter;
import com.personal.baton.adapter.out.external.link.BatonGoSettings;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BatonGoProperties.class)
public class BatonGoClientConfig {

    static final String BATON_GO_REST_CLIENT = "batonGoRestClient";

    @Bean
    BatonGoSettings batonGoSettings(BatonGoProperties properties) {
        return BatonGoSettings.from(properties);
    }

    @Bean(BATON_GO_REST_CLIENT)
    RestClient batonGoRestClient(
            BatonGoSettings settings,
            RestClient.Builder restClientBuilder,
            ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder,
            HttpClientSettings httpClientSettings
    ) {
        if (!settings.enabled()) {
            return restClientBuilder.build();
        }
        HttpClientSettings clientSettings = httpClientSettings
                .withTimeouts(settings.connectTimeout(), settings.readTimeout())
                .withRedirects(HttpRedirects.DONT_FOLLOW);
        return restClientBuilder
                .requestFactory(requestFactoryBuilder.build(clientSettings))
                .build();
    }

    @Bean
    BatonGoRoleResourceLinkAdapter batonGoRoleResourceLinkAdapter(
            BatonGoSettings settings,
            @Qualifier(BATON_GO_REST_CLIENT) RestClient restClient
    ) {
        return new BatonGoRoleResourceLinkAdapter(settings, restClient);
    }
}
