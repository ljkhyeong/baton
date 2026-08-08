package com.personal.baton.adapter.in.web.security;

import com.personal.baton.adapter.in.web.auth.AuthController;
import com.personal.baton.adapter.in.web.roundauth.ParticipationGrantController;
import com.personal.baton.adapter.in.web.roundauth.RoundAdministrationController;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

public final class AccountSessionRequestMatchers {

    private static final RequestMatcher LOCAL_LOGIN = path(
            HttpMethod.POST,
            AuthController.LOCAL_SESSION_PATH
    );
    private static final RequestMatcher ROUND_GRANT_REFRESH = path(
            HttpMethod.POST,
            ParticipationGrantController.REFRESH_PATH_PATTERN
    );
    private static final RequestMatcher AUTH_MUTATION = path(
            HttpMethod.POST,
            "/api/v1/auth/**"
    );
    private static final RequestMatcher ROUND_MEMBERSHIP_READ = path(
            HttpMethod.GET,
            RoundAdministrationController.CURRENT_MEMBERSHIP_PATH
    );
    private static final RequestMatcher ROUND_MEMBERSHIP_CLAIM = path(
            HttpMethod.POST,
            RoundAdministrationController.MEMBERSHIP_CLAIMS_PATH
    );
    private static final RequestMatcher ROUND_ROOM_MAPPING_CREATE = path(
            HttpMethod.POST,
            RoundAdministrationController.ROOM_MAPPINGS_PATH
    );
    private static final RequestMatcher ROUND_ROOM_MAPPING_DELETE = path(
            HttpMethod.DELETE,
            RoundAdministrationController.ROOM_MAPPING_PATH_PATTERN
    );
    private static final RequestMatcher SAME_ORIGIN_SESSION_MUTATION = new OrRequestMatcher(
            AUTH_MUTATION,
            ROUND_MEMBERSHIP_CLAIM,
            ROUND_ROOM_MAPPING_CREATE,
            ROUND_ROOM_MAPPING_DELETE
    );
    private static final RequestMatcher ACCOUNT_SESSION_REQUIRED = new OrRequestMatcher(
            ROUND_GRANT_REFRESH,
            ROUND_MEMBERSHIP_READ,
            ROUND_MEMBERSHIP_CLAIM,
            ROUND_ROOM_MAPPING_CREATE,
            ROUND_ROOM_MAPPING_DELETE
    );

    private AccountSessionRequestMatchers() {
    }

    public static RequestMatcher localLogin() {
        return LOCAL_LOGIN;
    }

    public static RequestMatcher roundGrantRefresh() {
        return ROUND_GRANT_REFRESH;
    }

    public static RequestMatcher sameOriginSessionMutation() {
        return SAME_ORIGIN_SESSION_MUTATION;
    }

    public static RequestMatcher accountSessionRequired() {
        return ACCOUNT_SESSION_REQUIRED;
    }

    private static RequestMatcher path(HttpMethod method, String pattern) {
        return PathPatternRequestMatcher.pathPattern(method, pattern);
    }
}
