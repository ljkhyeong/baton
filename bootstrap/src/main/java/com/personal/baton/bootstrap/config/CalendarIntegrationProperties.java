package com.personal.baton.bootstrap.config;

import com.personal.baton.adapter.out.external.http.ExternalHttpOrigin;
import com.personal.baton.application.calendar.port.in.MaintainCalendarSeasonMetadataUseCase.Mode;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("baton.calendar")
public record CalendarIntegrationProperties(
        boolean captureEnabled,
        boolean backfillEnabled,
        boolean deliveryEnabled,
        boolean seasonMetadataEnabled,
        @DefaultValue("OFF") Mode seasonMetadataMaintenance,
        boolean recoveryPreparationEnabled,
        @DefaultValue("") String recoveryRunId,
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

    void validateRecoveryConfiguration() {
        Optional<UUID> parsedRecoveryRunId = parsedRecoveryRunId();
        if (recoveryPreparationEnabled && (
                parsedRecoveryRunId.isEmpty()
                        || !captureEnabled
                        || !backfillEnabled
                        || !seasonMetadataEnabled
                        || seasonMetadataMaintenance != Mode.REPLAY
                        || deliveryEnabled
        )) {
            throw new IllegalStateException(
                    "CAL 복구 준비는 복구 ID, 캡처·일정 보정·시즌 이름과 REPLAY를 켜고 전달을 끈 상태에서 실행해야 합니다"
            );
        }
        if (parsedRecoveryRunId.isPresent() && deliveryEnabled
                && (!captureEnabled || !seasonMetadataEnabled)) {
            throw new IllegalStateException(
                    "CAL 복구 완료 확인은 캡처와 시즌 이름 연동을 켠 상태에서 실행해야 합니다"
            );
        }
    }

    public Optional<UUID> parsedRecoveryRunId() {
        if (recoveryRunId.isBlank()) {
            return Optional.empty();
        }
        try {
            UUID parsed = UUID.fromString(recoveryRunId);
            if (!parsed.toString().equalsIgnoreCase(recoveryRunId)) {
                throw new IllegalArgumentException();
            }
            return Optional.of(parsed);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("CAL 복구 ID는 표준 UUID여야 합니다", exception);
        }
    }

    URI requiredBaseUri() {
        return ExternalHttpOrigin.requireHttps("CAL base URL", baseUrl);
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
                + ", recoveryPreparationEnabled=" + recoveryPreparationEnabled
                + ", recoveryRunId=" + (recoveryRunId.isBlank() ? "<unset>" : "<configured>")
                + ", baseUrl=<redacted>, bearerToken=<redacted>, connectTimeout="
                + connectTimeout + ", readTimeout=" + readTimeout + "]";
    }
}
