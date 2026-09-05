package com.personal.baton.adapter.in.web.security;

import com.personal.baton.adapter.in.web.auth.AuthController;
import com.personal.baton.adapter.in.web.auth.CurrentAuthenticatedAccount;
import com.personal.baton.adapter.in.web.workspace.ResourceVerificationController;
import com.personal.baton.adapter.in.web.workspace.WorkspaceNotificationController;
import com.personal.baton.adapter.in.web.auth.AccountSecurityController;
import com.personal.baton.adapter.in.web.brief.BriefEditionController;
import com.personal.baton.adapter.in.web.roundauth.ParticipationGrantController;
import com.personal.baton.adapter.in.web.roundauth.RoundAdministrationController;
import java.util.Set;
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
    private static final RequestMatcher ACCOUNT_SECURITY_READ = pathPattern(
            HttpMethod.GET,
            AccountSecurityController.ACCOUNT_PATH
    );
    private static final RequestMatcher ACCOUNT_PASSWORD_CHANGE = pathPattern(
            HttpMethod.POST,
            AccountSecurityController.LOCAL_PASSWORD_CHANGES_PATH
    );
    private static final RequestMatcher ACCOUNT_SESSION_REVOCATION = pathPattern(
            HttpMethod.POST,
            AccountSecurityController.SESSION_REVOCATIONS_PATH
    );
    private static final RequestMatcher TEAM_ACCESS = new OrRequestMatcher(pathPattern("/api/v1/team-access/**"), pathPattern("/api/v1/team-invitations/**"));
    private static final RequestMatcher HAS_ACCOUNT_SESSION = request -> CurrentAuthenticatedAccount.accountId().isPresent();
    private static final RequestMatcher UNSAFE = request -> !Set.of("GET", "HEAD", "OPTIONS", "TRACE").contains(request.getMethod());
    private static final RequestMatcher WORKSPACE_ACCOUNT_MUTATION = new AndRequestMatcher(
            pathPattern("/api/v1/teams/{teamId}/seasons/{seasonId}/**"), HAS_ACCOUNT_SESSION, UNSAFE);
    private static final RequestMatcher NOTIFICATION_READ = pathPattern(HttpMethod.POST, WorkspaceNotificationController.READ_PATH);
    private static final RequestMatcher NOTIFICATION_INBOX = pathPattern(HttpMethod.GET, WorkspaceNotificationController.PATH);
    private static final RequestMatcher RESOURCE_VERIFICATION = pathPattern(HttpMethod.POST, ResourceVerificationController.PATH);
    private static final RequestMatcher SAME_ORIGIN_SESSION_MUTATION = new OrRequestMatcher(
            AUTH_MUTATION,
            WORKSPACE_ACCOUNT_MUTATION,
            new AndRequestMatcher(TEAM_ACCESS, UNSAFE),
            NOTIFICATION_READ,
            RESOURCE_VERIFICATION,
            ROUND_MEMBERSHIP_CLAIM,
            ROUND_ROOM_MAPPING_CREATE,
            ROUND_ROOM_MAPPING_DELETE,
            BRIEF_EDITION_GENERATION
    );
    private static final RequestMatcher ACCOUNT_SESSION_REQUIRED = new OrRequestMatcher(
            ACCOUNT_SECURITY_READ,
            TEAM_ACCESS,
            NOTIFICATION_READ,
            NOTIFICATION_INBOX,
            RESOURCE_VERIFICATION,
            ACCOUNT_PASSWORD_CHANGE,
            ACCOUNT_SESSION_REVOCATION,
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
                    new NegatedRequestMatcher(ACCOUNT_SESSION_REQUIRED),
                    new NegatedRequestMatcher(HAS_ACCOUNT_SESSION)
            );

    private AccountSessionRequestMatchers() {
    }

    public static RequestMatcher workspaceAccountMutation() {
        return new AndRequestMatcher(WORKSPACE_ACCOUNT_MUTATION, new NegatedRequestMatcher(ACCOUNT_SESSION_REQUIRED));
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
