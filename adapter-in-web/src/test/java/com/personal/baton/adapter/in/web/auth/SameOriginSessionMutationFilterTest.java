package com.personal.baton.adapter.in.web.auth;

import com.personal.baton.adapter.in.web.roundauth.RoundAdministrationController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class SameOriginSessionMutationFilterTest {

    private final SameOriginSessionMutationFilter filter =
            new SameOriginSessionMutationFilter();

    @DisplayName("ROUND membership claim은 exact same-origin이 없으면 controller 전에 거부한다")
    @Test
    void rejectsRoundMembershipClaimWithoutOriginEvidence() throws Exception {
        MockHttpServletRequest request = request(
                "POST",
                RoundAdministrationController.MEMBERSHIP_CLAIMS_PATH
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("ORIGIN_DENIED");
        assertThat(chain.getRequest()).isNull();
    }

    @DisplayName("ROUND room mapping 종료도 exact same-origin에서만 통과한다")
    @Test
    void admitsSameOriginRoundMappingDeletion() throws Exception {
        MockHttpServletRequest request = request(
                "DELETE",
                RoundAdministrationController.ROOM_MAPPINGS_PATH + "/abcd-efgh-jkmn"
        );
        request.addHeader(HttpHeaders.ORIGIN, "https://baton.example");
        request.addHeader("Sec-Fetch-Site", "same-origin");
        request.setScheme("https");
        request.setServerName("baton.example");
        request.setServerPort(443);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @DisplayName("공유 access-key 전용 workspace mutation은 기존 정책을 바꾸지 않는다")
    @Test
    void leavesNonSessionWorkspaceMutationUntouched() throws Exception {
        MockHttpServletRequest request = request(
                "POST",
                "/api/v1/teams/00000000-0000-0000-0000-000000000001/seasons/"
                        + "00000000-0000-0000-0000-000000000002/roles"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    private MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }
}
