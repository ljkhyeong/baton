package com.personal.baton.adapter.out.external.brief;

import com.personal.baton.adapter.out.external.http.OutboundRestClients;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class BriefRestClientFactory {

    private final OutboundRestClients restClients;

    public BriefRestClientFactory(OutboundRestClients restClients) {
        this.restClients = restClients;
    }

    public RestClientBriefContinuityClient createContinuityClient(
            URI baseUri,
            String bearerToken,
            Duration connectTimeout,
            Duration readTimeout
    ) {
        return new RestClientBriefContinuityClient(createRestClient(baseUri, bearerToken, connectTimeout, readTimeout));
    }

    /** 비공개 서비스 경로의 인증서는 {@code sslBundle}로만 신뢰한다. 없으면 JVM 기본 신뢰 저장소를 쓴다. */
    public RestClientBriefServiceClient createEditionServiceClient(
            URI baseUri,
            String bearerToken,
            Duration connectTimeout,
            Duration readTimeout,
            SslBundle sslBundle
    ) {
        RestClient restClient = sslBundle == null
                ? createRestClient(baseUri, bearerToken, connectTimeout, readTimeout)
                : restClients.withBearer(baseUri, bearerToken, connectTimeout, readTimeout, sslBundle);
        return new RestClientBriefServiceClient(restClient);
    }

    private RestClient createRestClient(URI baseUri, String bearerToken, Duration connectTimeout, Duration readTimeout) {
        URI requiredBaseUri = Objects.requireNonNull(baseUri, "BRIEF base URI는 필수입니다");
        return bearerToken == null
                ? restClients.builder(requiredBaseUri, connectTimeout, readTimeout).build()
                : restClients.withBearer(requiredBaseUri, bearerToken, connectTimeout, readTimeout);
    }
}
