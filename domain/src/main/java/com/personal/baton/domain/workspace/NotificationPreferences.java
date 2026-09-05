package com.personal.baton.domain.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;

@Entity
@Table(name = "notification_preferences")
public class NotificationPreferences {
    @Id @Column(name = "account_id", columnDefinition = "binary(16)")
    private UUID accountId;
    @Column(name = "deadline_soon_enabled", nullable = false)
    private boolean deadlineSoonEnabled = true;
    @Column(name = "overdue_enabled", nullable = false)
    private boolean overdueEnabled = true;
    @Column(name = "handoff_enabled", nullable = false)
    private boolean handoffEnabled = true;
    @Column(name = "deadline_lead_hours", nullable = false)
    private int deadlineLeadHours = 24;
    @Version @Column(nullable = false)
    private Long version;
    protected NotificationPreferences() {}
    public static NotificationPreferences defaults(UUID accountId) {
        var value = new NotificationPreferences(); value.accountId = accountId; return value;
    }
    public void configure(boolean deadlineSoonEnabled, boolean overdueEnabled, boolean handoffEnabled, int deadlineLeadHours) {
        if (deadlineLeadHours < 1 || deadlineLeadHours > 168) throw new DomainValidationException("마감 알림은 1~168시간 전으로 지정해 주세요");
        this.deadlineSoonEnabled = deadlineSoonEnabled; this.overdueEnabled = overdueEnabled;
        this.handoffEnabled = handoffEnabled; this.deadlineLeadHours = deadlineLeadHours;
    }
    public boolean includes(WorkspaceNotificationKind kind) {
        return switch (kind) { case DEADLINE_SOON -> deadlineSoonEnabled; case OVERDUE -> overdueEnabled; case HANDOFF_REQUEST -> handoffEnabled; };
    }
    public UUID getAccountId() { return accountId; }
    public boolean isDeadlineSoonEnabled() { return deadlineSoonEnabled; }
    public boolean isOverdueEnabled() { return overdueEnabled; }
    public boolean isHandoffEnabled() { return handoffEnabled; }
    public int getDeadlineLeadHours() { return deadlineLeadHours; }
    public Long getVersion() { return version; }
}
