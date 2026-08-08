package com.personal.baton.application.roundauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.personal.baton.application.roundauth.error.ParticipationGrantUnavailableException;
import com.personal.baton.application.roundauth.port.out.ParticipationGrantJwkSetProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ParticipationGrantJwkSetServiceTest {

    @Test
    @DisplayName("application JWK 조회 경계는 provider-neutral JSON을 그대로 반환한다")
    void returnProviderNeutralJwkSetJson() {
        ParticipationGrantJwkSetProvider provider = mock(ParticipationGrantJwkSetProvider.class);
        when(provider.readPublicJwkSetJson()).thenReturn("{\"keys\":[]}");
        ParticipationGrantJwkSetService service = new ParticipationGrantJwkSetService(provider);

        assertThat(service.readPublicJwkSetJson()).isEqualTo("{\"keys\":[]}");
    }

    @Test
    @DisplayName("비활성 provider의 실패를 빈 JWK Set으로 완화하지 않는다")
    void preserveFailClosedProviderFailure() {
        ParticipationGrantJwkSetProvider provider = mock(ParticipationGrantJwkSetProvider.class);
        ParticipationGrantUnavailableException cause =
                new ParticipationGrantUnavailableException("비활성화");
        when(provider.readPublicJwkSetJson()).thenThrow(cause);
        ParticipationGrantJwkSetService service = new ParticipationGrantJwkSetService(provider);

        assertThatThrownBy(service::readPublicJwkSetJson).isSameAs(cause);
    }
}
