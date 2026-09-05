package com.personal.baton.domain.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "resource_review_schedules")
public class ResourceReviewSchedule {
    @Id @Column(name = "resource_id", columnDefinition = "binary(16)")
    private UUID resourceId;
    @Column(name = "interval_days")
    private Integer intervalDays;
    @Column(name = "next_review_on")
    private LocalDate nextReviewOn;
    @Version @Column(nullable = false)
    private Long version;
    protected ResourceReviewSchedule() {}
    public static ResourceReviewSchedule create(UUID resourceId) {
        var schedule = new ResourceReviewSchedule(); schedule.resourceId = resourceId; return schedule;
    }
    public void configure(Integer intervalDays, LocalDate nextReviewOn) {
        if ((intervalDays == null) != (nextReviewOn == null) || (intervalDays != null && (intervalDays < 1 || intervalDays > 365))) {
            throw new DomainValidationException("재확인 주기는 1~365일과 다음 확인일을 함께 지정하거나 모두 해제해 주세요");
        }
        this.intervalDays = intervalDays; this.nextReviewOn = nextReviewOn;
    }
    public void confirmOn(LocalDate today) { if (intervalDays != null) nextReviewOn = today.plusDays(intervalDays); }
    public boolean isDueOn(LocalDate today) { return nextReviewOn != null && !today.isBefore(nextReviewOn); }
    public UUID getResourceId() { return resourceId; }
    public Integer getIntervalDays() { return intervalDays; }
    public LocalDate getNextReviewOn() { return nextReviewOn; }
    public Long getVersion() { return version; }
}
