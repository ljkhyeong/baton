package com.personal.baton.adapter.in.web.roundauth;

import com.personal.baton.application.roundauth.error.RoundParticipationDeniedException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

class RoundAuthorizationExceptionHandlerTest {

    private static final String ROOM_ID = "bcdf-ghjk-mnpq";

    private final RoundAuthorizationExceptionHandler handler =
            new RoundAuthorizationExceptionHandler();

    @DisplayName("roomId가 있는 mapping 종료 요청이어도 참여권 cookie를 만료하지 않는다")
    @Test
    void keepsCookieForRoomMappingDeletion() {
        MockHttpServletRequest request = mappedRequest(
                RoundAdministrationController.ROOM_MAPPING_PATH_PATTERN,
                ROOM_ID
        );

        var response = handler.handleParticipationDenied(
                new RoundParticipationDeniedException(),
                request
        );

        assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).isNull();
    }

    @DisplayName("참여권 갱신 mapping의 roomId가 canonical 형식이 아니면 cookie를 만들지 않는다")
    @Test
    void rejectsNonCanonicalRefreshRoomIdForCookie() {
        MockHttpServletRequest request = mappedRequest(
                ParticipationGrantController.REFRESH_PATH_PATTERN,
                "invalid-room-id"
        );

        var response = handler.handleParticipationDenied(
                new RoundParticipationDeniedException(),
                request
        );

        assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).isNull();
    }

    private MockHttpServletRequest mappedRequest(String pattern, String roomId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
        request.setAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("roomId", roomId)
        );
        return request;
    }
}
