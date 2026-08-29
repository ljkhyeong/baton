package com.personal.baton.adapter.out.external.brief;

import com.personal.baton.application.brief.BriefContinuityDelivery;
import com.personal.baton.application.brief.port.out.BriefContinuityClient;
import java.util.Objects;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public final class RestClientBriefContinuityClient implements BriefContinuityClient {

    private static final String EVENT_PATH = "/api/v1/events";

    private final RestClient restClient;

    public RestClientBriefContinuityClient(RestClient restClient) {
        this.restClient = Objects.requireNonNull(restClient, "BRIEF RestClient는 필수입니다");
    }

    @Override
    public DeliveryResult deliver(BriefContinuityDelivery delivery) {
        Objects.requireNonNull(delivery, "BRIEF delivery는 필수입니다");
        try {
            return restClient.post()
                    .uri(EVENT_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON)
                    .body(delivery.event())
                    .exchange((ignoredRequest, response) -> classify(response.getStatusCode()));
        } catch (ResourceAccessException exception) {
            return DeliveryResult.retryable("BRIEF_NETWORK_FAILURE");
        } catch (RestClientException exception) {
            return DeliveryResult.retryable("BRIEF_CLIENT_FAILURE");
        }
    }

    private DeliveryResult classify(HttpStatusCode status) {
        if (status.value() == 200 || status.value() == 202) {
            return DeliveryResult.delivered(httpCode(status));
        }
        if (status.value() == 429 || status.is5xxServerError()) {
            return DeliveryResult.retryable(httpCode(status));
        }
        return DeliveryResult.permanentFailure(httpCode(status));
    }

    private String httpCode(HttpStatusCode status) {
        return "HTTP_" + status.value();
    }
}
