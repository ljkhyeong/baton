package com.personal.baton.adapter.out.persistence.calendar;

import com.personal.baton.application.calendar.CalendarSubscriptionException;
import com.personal.baton.application.calendar.CalendarSubscriptionException.Reason;
import com.personal.baton.application.calendar.port.out.CalendarSubscriptionStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcCalendarSubscriptionStore implements CalendarSubscriptionStore {
    private static final String SELECT = """
            SELECT BIN_TO_UUID(account_id) account_id, BIN_TO_UUID(team_id) team_id,
                   BIN_TO_UUID(season_id) season_id, BIN_TO_UUID(subscription_id) subscription_id,
                   revoked, revocation_pending, lease_until
            FROM calendar_subscriptions
            """;
    private static final String OWNER = " WHERE account_id=UUID_TO_BIN(?) AND team_id=UUID_TO_BIN(?) AND season_id=UUID_TO_BIN(?)";
    private final JdbcTemplate jdbc;
    public JdbcCalendarSubscriptionStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<Stored> find(Owner owner) {
        return jdbc.query(SELECT + OWNER, this::read, args(owner)).stream().findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Claim claim(Owner owner, boolean create, boolean revoking, Instant now) {
        if (create) {
            jdbc.update("""
                    INSERT INTO calendar_subscriptions (account_id, team_id, season_id, subscription_id)
                    VALUES (UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?))
                    ON DUPLICATE KEY UPDATE subscription_id=subscription_id
                    """, owner.accountId().toString(), owner.teamId().toString(), owner.seasonId().toString(), UUID.randomUUID().toString());
        }
        Stored stored = jdbc.query(SELECT + OWNER + " FOR UPDATE", this::read, args(owner)).stream()
                .findFirst().orElseThrow(() -> new CalendarSubscriptionException(Reason.NOT_FOUND));
        if (stored.leaseUntil() != null && stored.leaseUntil().isAfter(now)
                || stored.revocationPending() && !revoking) {
            throw new CalendarSubscriptionException(Reason.IN_PROGRESS);
        }
        UUID subscriptionId = create && stored.revoked() ? UUID.randomUUID() : stored.subscriptionId();
        UUID token = UUID.randomUUID();
        Instant leaseUntil = now.plusSeconds(60);
        jdbc.update("""
                UPDATE calendar_subscriptions SET subscription_id=UUID_TO_BIN(?), operation_token=UUID_TO_BIN(?),
                       lease_until=?, revoked=?
                """ + OWNER, subscriptionId.toString(), token.toString(), utc(leaseUntil), create ? false : stored.revoked(),
                owner.accountId().toString(), owner.teamId().toString(), owner.seasonId().toString());
        return new Claim(new Stored(owner, subscriptionId, create ? false : stored.revoked(), stored.revocationPending(), leaseUntil), token);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean release(Claim claim, boolean revoked) {
        int changed = jdbc.update("""
                UPDATE calendar_subscriptions SET operation_token=NULL, lease_until=NULL,
                       revoked=?, revocation_pending=IF(?,FALSE,revocation_pending)
                WHERE subscription_id=UUID_TO_BIN(?) AND operation_token=UUID_TO_BIN(?)
                """, revoked, revoked, claim.subscription().subscriptionId().toString(), claim.token().toString());
        return changed == 1 && find(claim.subscription().owner()).filter(row -> !row.revocationPending()).isPresent();
    }

    @Override
    public void requestRevocation(Owner owner) {
        jdbc.update("UPDATE calendar_subscriptions SET revocation_pending=TRUE" + OWNER,
                args(owner));
    }

    @Override
    public void requestMemberRevocation(UUID teamId, UUID memberId) {
        jdbc.update("""
                UPDATE calendar_subscriptions subscription JOIN account_team_memberships membership
                ON membership.account_id=subscription.account_id AND membership.team_id=subscription.team_id
                SET subscription.revocation_pending=TRUE
                WHERE membership.team_id=UUID_TO_BIN(?) AND membership.member_id=UUID_TO_BIN(?)
                  AND subscription.revoked=FALSE
                """, teamId.toString(), memberId.toString());
    }

    @Override
    public List<Owner> pendingRevocations(Instant now) {
        return jdbc.query(SELECT + """
                 WHERE (revocation_pending=TRUE OR (revoked=FALSE AND NOT EXISTS (
                     SELECT 1 FROM account_team_memberships membership JOIN members member
                     ON member.id=membership.member_id AND member.team_id=membership.team_id
                     WHERE membership.account_id=calendar_subscriptions.account_id
                     AND membership.team_id=calendar_subscriptions.team_id AND member.deactivated_at IS NULL
                 ))) AND (lease_until IS NULL OR lease_until<=?)
                 ORDER BY account_id,season_id LIMIT 50
                """,
                this::read, utc(now)).stream().map(Stored::owner).toList();
    }

    private Stored read(ResultSet rs, int ignored) throws SQLException {
        LocalDateTime lease = rs.getObject("lease_until", LocalDateTime.class);
        return new Stored(new Owner(UUID.fromString(rs.getString("account_id")), UUID.fromString(rs.getString("team_id")),
                UUID.fromString(rs.getString("season_id"))), UUID.fromString(rs.getString("subscription_id")),
                rs.getBoolean("revoked"), rs.getBoolean("revocation_pending"), lease == null ? null : lease.toInstant(ZoneOffset.UTC));
    }
    private Object[] args(Owner owner) { return new Object[] { owner.accountId().toString(), owner.teamId().toString(), owner.seasonId().toString() }; }
    private LocalDateTime utc(Instant instant) { return LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }
}
