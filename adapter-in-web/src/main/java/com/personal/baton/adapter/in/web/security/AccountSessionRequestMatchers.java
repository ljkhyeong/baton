package com.personal.baton.adapter.in.web.security;

import com.personal.baton.adapter.in.web.auth.AuthController;
import com.personal.baton.adapter.in.web.auth.AccountDeactivationController;
import com.personal.baton.adapter.in.web.auth.CurrentAuthenticatedAccount;
import com.personal.baton.adapter.in.web.workspace.ResourceVerificationController;
import com.personal.baton.adapter.in.web.workspace.NotificationPreferencesController;
import com.personal.baton.adapter.in.web.workspace.WorkspaceNotificationController;
import com.personal.baton.adapter.in.web.auth.AccountSecurityController;
import com.personal.baton.adapter.in.web.calendar.CalendarSubscriptionController;
import com.personal.baton.adapter.in.web.roundauth.ParticipationGrantController;
import com.personal.baton.adapter.in.web.roundauth.RoundAdministrationController;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import static org.springframework.security.web.csrf.CsrfFilter.DEFAULT_CSRF_MATCHER;
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
    private static final RequestMatcher NOTIFICATION_PREFERENCES = pathPattern(NotificationPreferencesController.PATH);
    private static final RequestMatcher TEAM_ACCESS = new OrRequestMatcher(pathPattern("/api/v1/team-access/**"), pathPattern("/api/v1/team-invitations/**"));
    private static final RequestMatcher HAS_ACCOUNT_SESSION = request -> CurrentAuthenticatedAccount.accountId().isPresent();
    private static final RequestMatcher WORKSPACE_ACCOUNT_MUTATION = new AndRequestMatcher(
            pathPattern("/api/v1/teams/{teamId}/seasons/{seasonId}/**"), HAS_ACCOUNT_SESSION, DEFAULT_CSRF_MATCHER);
    // 같은 경로의 조회는 공유 키로도 허용하므로 자료 재확인은 기록 요청만 계정 세션을 요구한다.
    private static final RequestMatcher RESOURCE_VERIFICATION = new OrRequestMatcher(
            pathPattern(HttpMethod.POST, ResourceVerificationController.PATH),
            pathPattern(HttpMethod.POST, ResourceVerificationController.SCHEDULE_PATH));
    // 나머지 계정 전용 경로는 메서드와 관계없이 묶어 HEAD 같은 다른 메서드도 계정 세션을 요구한다.
    private static final RequestMatcher ACCOUNT_SESSION_REQUIRED = new OrRequestMatcher(
            pathPattern(AccountDeactivationController.PATH),
            pathPattern(AccountSecurityController.ACCOUNT_PATH),
            pathPattern(AccountSecurityController.LOCAL_PASSWORD_CHANGES_PATH),
            pathPattern(AccountSecurityController.SESSION_REVOCATIONS_PATH),
            NOTIFICATION_PREFERENCES,
            TEAM_ACCESS,
            pathPattern(WorkspaceNotificationController.PATH),
            pathPattern(WorkspaceNotificationController.READ_PATH),
            RESOURCE_VERIFICATION,
            ROUND_GRANT_REFRESH,
            pathPattern(RoundAdministrationController.CURRENT_MEMBERSHIP_PATH),
            pathPattern(RoundAdministrationController.MEMBERSHIP_CLAIMS_PATH),
            pathPattern(RoundAdministrationController.ROOM_MAPPINGS_PATH),
            pathPattern(RoundAdministrationController.ROOM_MAPPING_PATH_PATTERN),
            pathPattern("/api/v1/teams/{teamId}/seasons/{seasonId}/brief/**"),
            pathPattern(CalendarSubscriptionController.LIST_PATH),
            pathPattern(CalendarSubscriptionController.PATH),
            pathPattern(CalendarSubscriptionController.ROTATE_PATH)
    );
    // ROUND 참여권 갱신은 RoundGrantAdmissionFilter가 같은 출처와 인증을 함께 확인한다.
    private static final RequestMatcher SAME_ORIGIN_SESSION_MUTATION = new OrRequestMatcher(
            AUTH_MUTATION,
            WORKSPACE_ACCOUNT_MUTATION,
            new AndRequestMatcher(ACCOUNT_SESSION_REQUIRED, DEFAULT_CSRF_MATCHER, new NegatedRequestMatcher(ROUND_GRANT_REFRESH))
    );
    private static final RequestMatcher WORKSPACE_ACCOUNT_HEADER_REQUIRED = new AndRequestMatcher(
            WORKSPACE_ACCOUNT_MUTATION, new NegatedRequestMatcher(ACCOUNT_SESSION_REQUIRED));
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
        return WORKSPACE_ACCOUNT_HEADER_REQUIRED;
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
