package com.personal.baton.adapter.in.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.personal.baton.adapter.in.web.roundauth.ParticipationGrantController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;

class AccountSessionRequestMatchersTest {

    @DisplayName("ROUND refresh matcher는 controller path의 한 roomId만 추출한다")
    @Test
    void matchesControllerShapedRoundRefresh() {
        RequestMatcher matcher = AccountSessionRequestMatchers.roundGrantRefresh();
        MockHttpServletRequest request = request(
                "POST",
                "/round/rooms/abcd-efgh-jkmn/participation-grant/refresh"
        );

        RequestMatcher.MatchResult result = matcher.matcher(request);

        assertThat(result.isMatch()).isTrue();
        assertThat(result.getVariables()).containsEntry("roomId", "abcd-efgh-jkmn");
    }

    @DisplayName("ROUND refresh matcher는 다른 method와 중첩 room 경로를 허용하지 않는다")
    @Test
    void rejectsRefreshLookalikes() {
        RequestMatcher matcher = AccountSessionRequestMatchers.roundGrantRefresh();

        assertThat(matcher.matches(request(
                "GET",
                "/round/rooms/abcd-efgh-jkmn/participation-grant/refresh"
        ))).isFalse();
        assertThat(matcher.matches(request(
                "POST",
                "/round/rooms/parent/child/participation-grant/refresh"
        ))).isFalse();
        assertThat(matcher.matches(request(
                "POST",
                ParticipationGrantController.JWK_SET_PATH
        ))).isFalse();
    }

    private MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }
}
