package com.personal.baton.bootstrap.config;

import com.personal.baton.application.calendar.port.in.MaintainCalendarSeasonMetadataUseCase.Mode;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("baton.calendar")
public record CalendarIntegrationProperties(
        boolean captureEnabled,
        boolean backfillEnabled,
        boolean deliveryEnabled,
        boolean seasonMetadataEnabled,
        @DefaultValue("OFF") Mode seasonMetadataMaintenance,
        @DefaultValue("") String baseUrl,
        @DefaultValue("") String bearerToken,
        @DefaultValue("PT2S") Duration connectTimeout,
        @DefaultValue("PT5S") Duration readTimeout
) {

    void validateSeasonMetadataMaintenance() {
        if (seasonMetadataMaintenance != Mode.OFF && (!seasonMetadataEnabled || !captureEnabled || deliveryEnabled)) {
            throw new IllegalStateException("CAL 이름 보정·재전달 준비는 이름 연동과 캡처를 켜고 전달을 끈 상태에서 실행해야 합니다");
        }
    }

    URI requiredBaseUri() {
        return OutboundHttpSettings.requireHttpsOrigin("CAL", baseUrl);
    }

    String requiredBearerToken() {
        return OutboundHttpSettings.requireBearerToken("CAL", bearerToken);
    }

    Duration requiredConnectTimeout() {
        return OutboundHttpSettings.requirePositiveTimeout(
                "CAL",
                "connect timeout",
                connectTimeout
        );
    }

    Duration requiredReadTimeout() {
        return OutboundHttpSettings.requirePositiveTimeout(
                "CAL",
                "read timeout",
                readTimeout
        );
    }

    void validateRequestTimeoutBudget(Duration connect, Duration read) {
        OutboundHttpSettings.validateRequestTimeoutBudget("CAL", connect, read);
    }

    @Override
    public String toString() {
        return "CalendarIntegrationProperties[captureEnabled=" + captureEnabled
                + ", backfillEnabled=" + backfillEnabled
                + ", deliveryEnabled=" + deliveryEnabled
                + ", seasonMetadataEnabled=" + seasonMetadataEnabled
                + ", seasonMetadataMaintenance=" + seasonMetadataMaintenance
                + ", baseUrl=<redacted>, bearerToken=<redacted>, connectTimeout="
                + connectTimeout + ", readTimeout=" + readTimeout + "]";
    }
}
