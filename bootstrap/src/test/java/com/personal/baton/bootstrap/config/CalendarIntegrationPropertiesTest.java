package com.personal.baton.bootstrap.config;

import com.personal.baton.application.calendar.port.in.MaintainCalendarSeasonMetadataUseCase.Mode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CalendarIntegrationPropertiesTest {

    private static final String TOKEN = "calendar-token-with-at-least-32-characters";

    @Test
    @DisplayName("CAL 전달 설정은 HTTPS 출처와 Bearer를 반환하고 문자열에서 자격 증명을 숨긴다")
    void acceptsSecureDeliverySettingsWithoutExposingBearer() {
        CalendarIntegrationProperties properties = properties(
                "https://calendar.internal",
                TOKEN
        );

        assertThat(properties.requiredBaseUri())
                .isEqualTo(URI.create("https://calendar.internal"));
        assertThat(properties.requiredBearerToken()).isEqualTo(TOKEN);
        assertThat(properties.toString()).doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("CAL 전달 설정은 평문 주소와 짧은 Bearer를 거부한다")
    void rejectsUnsafeDeliverySettings() {
        assertThatThrownBy(() -> properties("http://calendar.internal", TOKEN)
                .requiredBaseUri())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> properties("https://calendar.internal", "short-token")
                .requiredBearerToken())
                .isInstanceOf(IllegalStateException.class);
    }

    private CalendarIntegrationProperties properties(String baseUrl, String bearerToken) {
        return new CalendarIntegrationProperties(
                false,
                false,
                true,
                false,
                Mode.OFF,
                baseUrl,
                bearerToken,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5)
        );
    }
}
