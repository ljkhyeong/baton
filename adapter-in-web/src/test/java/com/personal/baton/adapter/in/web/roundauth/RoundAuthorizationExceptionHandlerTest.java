package com.personal.baton.adapter.in.web.roundauth;

import com.personal.baton.application.roundauth.error.RoundParticipationDeniedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class RoundAuthorizationExceptionHandlerTest {

    private static final String ROOM_ID = "bcdf-ghjk-mnpq";

    private final RoundAuthorizationExceptionHandler handler =
            new RoundAuthorizationExceptionHandler();

    @DisplayName("roomId가 있는 연결 종료 요청이어도 참여권 쿠키를 만료하지 않는다")
    @Test
    void keepsCookieForRoomMappingDeletion() {
        MockHttpServletRequest request = request(
                "DELETE",
                RoundAdministrationController.ROOM_MAPPING_PATH_PATTERN,
                ROOM_ID
        );

        var response = handler.handleParticipationDenied(
                new RoundParticipationDeniedException(),
                request
        );

        assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).isNull();
    }

    @DisplayName("참여권 갱신이 거절되면 해당 방의 참여권 쿠키를 만료한다")
    @Test
    void expiresCookieForRefreshDenial() {
        MockHttpServletRequest request = request(
                "POST",
                ParticipationGrantController.REFRESH_PATH_PATTERN,
                ROOM_ID
        );

        var response = handler.handleParticipationDenied(
                new RoundParticipationDeniedException(),
                request
        );

        assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
                .isEqualTo(RoundGrantCookie.expire(ROOM_ID).toString());
    }

    @DisplayName("참여권 갱신 연결의 roomId가 표준 형식이 아니면 쿠키를 만들지 않는다")
    @Test
    void rejectsNonCanonicalRefreshRoomIdForCookie() {
        MockHttpServletRequest request = request(
                "POST",
                ParticipationGrantController.REFRESH_PATH_PATTERN,
                "invalid-room-id"
        );

        var response = handler.handleParticipationDenied(
                new RoundParticipationDeniedException(),
                request
        );

        assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).isNull();
    }

    private MockHttpServletRequest request(String method, String pattern, String roomId) {
        return new MockHttpServletRequest(method, pattern.replace("{roomId}", roomId));
    }
}
