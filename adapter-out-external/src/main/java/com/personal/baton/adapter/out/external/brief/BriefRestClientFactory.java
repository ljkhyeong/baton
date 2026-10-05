package com.personal.baton.adapter.out.external.brief;

import com.personal.baton.adapter.out.external.http.OutboundRestClients;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
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

    public RestClientBriefServiceClient createEditionServiceClient(
            URI baseUri,
            String bearerToken,
            Duration connectTimeout,
            Duration readTimeout
    ) {
        return new RestClientBriefServiceClient(createRestClient(baseUri, bearerToken, connectTimeout, readTimeout));
    }

    private RestClient createRestClient(URI baseUri, String bearerToken, Duration connectTimeout, Duration readTimeout) {
        URI requiredBaseUri = Objects.requireNonNull(baseUri, "BRIEF base URI는 필수입니다");
        return bearerToken == null
                ? restClients.builder(requiredBaseUri, connectTimeout, readTimeout).build()
                : restClients.withBearer(requiredBaseUri, bearerToken, connectTimeout, readTimeout);
    }
}
