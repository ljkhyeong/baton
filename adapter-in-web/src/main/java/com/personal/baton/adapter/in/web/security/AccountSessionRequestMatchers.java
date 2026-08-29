package com.personal.baton.adapter.in.web.security;

import com.personal.baton.adapter.in.web.auth.AuthController;
import com.personal.baton.adapter.in.web.brief.BriefEditionController;
import com.personal.baton.adapter.in.web.roundauth.ParticipationGrantController;
import com.personal.baton.adapter.in.web.roundauth.RoundAdministrationController;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.pathPattern;

public final class AccountSessionRequestMatchers {

    private static final RequestMatcher LOCAL_LOGIN = pathPattern(
            HttpMethod.POST,
            AuthController.LOCAL_SESSION_PATH
    );
    private static final RequestMatcher ROUND_GRANT_REFRESH = pathPattern(
            HttpMethod.POST,
            ParticipationGrantController.REFRESH_PATH_PATTERN
    );
    private static final RequestMatcher AUTH_MUTATION = pathPattern(
            HttpMethod.POST,
            "/api/v1/auth/**"
    );
    private static final RequestMatcher ROUND_MEMBERSHIP_READ = pathPattern(
            HttpMethod.GET,
            RoundAdministrationController.CURRENT_MEMBERSHIP_PATH
    );
    private static final RequestMatcher ROUND_MEMBERSHIP_CLAIM = pathPattern(
            HttpMethod.POST,
            RoundAdministrationController.MEMBERSHIP_CLAIMS_PATH
    );
    private static final RequestMatcher ROUND_ROOM_MAPPING_CREATE = pathPattern(
            HttpMethod.POST,
            RoundAdministrationController.ROOM_MAPPINGS_PATH
    );
    private static final RequestMatcher ROUND_ROOM_MAPPING_READ = pathPattern(
            HttpMethod.GET,
            RoundAdministrationController.ROOM_MAPPINGS_PATH
    );
    private static final RequestMatcher ROUND_ROOM_MAPPING_DELETE = pathPattern(
            HttpMethod.DELETE,
            RoundAdministrationController.ROOM_MAPPING_PATH_PATTERN
    );
    private static final RequestMatcher BRIEF_EDITION_READ = pathPattern(
            HttpMethod.GET,
            BriefEditionController.LATEST_PATH
    );
    private static final RequestMatcher BRIEF_EDITION_GENERATION = pathPattern(
            HttpMethod.POST,
            BriefEditionController.GENERATION_PATH
    );
    private static final RequestMatcher SAME_ORIGIN_SESSION_MUTATION = new OrRequestMatcher(
            AUTH_MUTATION,
            ROUND_MEMBERSHIP_CLAIM,
            ROUND_ROOM_MAPPING_CREATE,
            ROUND_ROOM_MAPPING_DELETE,
            BRIEF_EDITION_GENERATION
    );
    private static final RequestMatcher ACCOUNT_SESSION_REQUIRED = new OrRequestMatcher(
            ROUND_GRANT_REFRESH,
            ROUND_MEMBERSHIP_READ,
            ROUND_MEMBERSHIP_CLAIM,
            ROUND_ROOM_MAPPING_READ,
            ROUND_ROOM_MAPPING_CREATE,
            ROUND_ROOM_MAPPING_DELETE,
            BRIEF_EDITION_READ,
            BRIEF_EDITION_GENERATION
    );
    private static final RequestMatcher WORKSPACE_CAPABILITY_WITHOUT_ACCOUNT_SESSION =
            new AndRequestMatcher(
                    pathPattern(
                            "/api/v1/teams/{teamId}/seasons/{seasonId}/**"
                    ),
                    new NegatedRequestMatcher(ACCOUNT_SESSION_REQUIRED)
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

    public static RequestMatcher workspaceCapabilityWithoutAccountSession() {
        return WORKSPACE_CAPABILITY_WITHOUT_ACCOUNT_SESSION;
    }
}
