package com.personal.baton.domain.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "role_handoffs")
public class RoleHandoff {

    @Id
    @Column(nullable = false, columnDefinition = "binary(16)")
    private UUID id;

    @Column(name = "team_id", nullable = false, columnDefinition = "binary(16)")
    private UUID teamId;

    @Column(name = "season_id", nullable = false, columnDefinition = "binary(16)")
    private UUID seasonId;

    @Column(name = "role_id", nullable = false, columnDefinition = "binary(16)")
    private UUID roleId;

    @Column(name = "from_member_id", nullable = false, columnDefinition = "binary(16)")
    private UUID fromMemberId;

    @Column(name = "to_member_id", nullable = false, columnDefinition = "binary(16)")
    private UUID toMemberId;

    @Column(name = "outgoing_assignment_start_date")
    private LocalDate outgoingAssignmentStartDate;

    @Column(name = "outgoing_assignment_end_date")
    private LocalDate outgoingAssignmentEndDate;

    @Column(name = "incoming_assignment_start_date", nullable = false)
    private LocalDate incomingAssignmentStartDate;

    @Column(name = "incoming_assignment_end_date")
    private LocalDate incomingAssignmentEndDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RoleHandoffStatus status;

    @Column(name = "prepared_at", nullable = false)
    private Instant preparedAt;

    @Column(name = "transferred_at")
    private Instant transferredAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "transferred_by_member_id", columnDefinition = "binary(16)")
    private UUID transferredByMemberId;

    @Column(name = "accepted_by_member_id", columnDefinition = "binary(16)")
    private UUID acceptedByMemberId;

    @Column(name = "cancelled_by_member_id", columnDefinition = "binary(16)")
    private UUID cancelledByMemberId;

    @Column(name = "snapshot_item_count")
    private Integer snapshotItemCount;

    @Column(name = "snapshot_incomplete_item_count")
    private Integer snapshotIncompleteItemCount;

    @Column(name = "snapshot_resource_count")
    private Integer snapshotResourceCount;

    @Column(name = "warning_acknowledged", nullable = false)
    private boolean warningAcknowledged;

    @Version
    @Column(nullable = false)
    private Long version;

    protected RoleHandoff() {
    }

    private RoleHandoff(
            UUID id,
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID fromMemberId,
            UUID toMemberId,
            LocalDate outgoingAssignmentStartDate,
            LocalDate outgoingAssignmentEndDate,
            LocalDate incomingAssignmentStartDate,
            LocalDate incomingAssignmentEndDate,
            Instant preparedAt
    ) {
        this.id = Objects.requireNonNull(id, "역할 인수인계 식별자는 필수입니다");
        this.teamId = Objects.requireNonNull(teamId, "팀 식별자는 필수입니다");
        this.seasonId = Objects.requireNonNull(seasonId, "시즌 식별자는 필수입니다");
        this.roleId = Objects.requireNonNull(roleId, "역할 식별자는 필수입니다");
        this.fromMemberId = Objects.requireNonNull(fromMemberId, "이전 담당자 식별자는 필수입니다");
        this.toMemberId = Objects.requireNonNull(toMemberId, "다음 담당자 식별자는 필수입니다");
        if (this.fromMemberId.equals(this.toMemberId)) {
            throw new DomainValidationException("이전 담당자와 다음 담당자는 달라야 합니다");
        }
        validateAssignmentRange(
                outgoingAssignmentStartDate,
                outgoingAssignmentEndDate,
                "이전 담당 기간"
        );
        LocalDate validatedIncomingStartDate = Objects.requireNonNull(
                incomingAssignmentStartDate,
                "다음 담당 시작일은 필수입니다"
        );
        validateAssignmentRange(
                validatedIncomingStartDate,
                incomingAssignmentEndDate,
                "다음 담당 기간"
        );
        this.outgoingAssignmentStartDate = outgoingAssignmentStartDate;
        this.outgoingAssignmentEndDate = outgoingAssignmentEndDate;
        this.incomingAssignmentStartDate = validatedIncomingStartDate;
        this.incomingAssignmentEndDate = incomingAssignmentEndDate;
        this.status = RoleHandoffStatus.PREPARING;
        this.preparedAt = Objects.requireNonNull(preparedAt, "인수인계 준비 시각은 필수입니다");
    }

    public static RoleHandoff prepare(
            UUID id,
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID fromMemberId,
            UUID toMemberId,
            LocalDate outgoingAssignmentStartDate,
            LocalDate outgoingAssignmentEndDate,
            LocalDate incomingAssignmentStartDate,
            LocalDate incomingAssignmentEndDate,
            Instant preparedAt
    ) {
        return new RoleHandoff(
                id,
                teamId,
                seasonId,
                roleId,
                fromMemberId,
                toMemberId,
                outgoingAssignmentStartDate,
                outgoingAssignmentEndDate,
                incomingAssignmentStartDate,
                incomingAssignmentEndDate,
                preparedAt
        );
    }

    public void transfer(
            UUID confirmedByMemberId,
            Instant transferredAt,
            int activeItemCount,
            int incompleteItemCount,
            int resourceCount,
            boolean warningAcknowledged
    ) {
        if (!canTransfer(confirmedByMemberId)) {
            throw new RoleHandoffTransitionException("이전 담당자만 준비 중인 인수인계를 전달할 수 있습니다");
        }
        if (hasWarnings(activeItemCount, incompleteItemCount, resourceCount)
                && !warningAcknowledged) {
            throw new DomainValidationException("미완료 항목과 빠진 자료 경고를 확인해야 인수인계를 전달할 수 있습니다");
        }
        Instant validatedTransferredAt = Objects.requireNonNull(
                transferredAt,
                "인수인계 전달 시각은 필수입니다"
        );
        if (validatedTransferredAt.isBefore(preparedAt)) {
            throw new DomainValidationException("인수인계 전달 시각은 준비 시각보다 이를 수 없습니다");
        }

        this.status = RoleHandoffStatus.TRANSFERRED;
        this.transferredByMemberId = confirmedByMemberId;
        this.transferredAt = validatedTransferredAt;
        this.snapshotItemCount = activeItemCount;
        this.snapshotIncompleteItemCount = incompleteItemCount;
        this.snapshotResourceCount = resourceCount;
        this.warningAcknowledged = warningAcknowledged;
    }

    public void accept(UUID confirmedByMemberId, Instant acceptedAt) {
        if (!canAccept(confirmedByMemberId)) {
            throw new RoleHandoffTransitionException("다음 담당자만 전달된 인수인계를 수락할 수 있습니다");
        }
        Instant validatedAcceptedAt = Objects.requireNonNull(acceptedAt, "인수인계 수락 시각은 필수입니다");
        if (validatedAcceptedAt.isBefore(transferredAt)) {
            throw new DomainValidationException("인수인계 수락 시각은 전달 시각보다 이를 수 없습니다");
        }

        this.status = RoleHandoffStatus.ACCEPTED;
        this.acceptedByMemberId = confirmedByMemberId;
        this.acceptedAt = validatedAcceptedAt;
    }

    public void cancel(UUID confirmedByMemberId, Instant cancelledAt) {
        if (!canCancel(confirmedByMemberId)) {
            throw new RoleHandoffTransitionException("이전 담당자만 열린 인수인계를 취소할 수 있습니다");
        }
        Instant validatedCancelledAt = Objects.requireNonNull(cancelledAt, "인수인계 취소 시각은 필수입니다");
        Instant lowerBound = transferredAt == null ? preparedAt : transferredAt;
        if (validatedCancelledAt.isBefore(lowerBound)) {
            throw new DomainValidationException("인수인계 취소 시각은 현재 단계가 시작된 시각보다 이를 수 없습니다");
        }

        this.status = RoleHandoffStatus.CANCELLED;
        this.cancelledByMemberId = confirmedByMemberId;
        this.cancelledAt = validatedCancelledAt;
    }

    private boolean canTransfer(UUID confirmedByMemberId) {
        return status == RoleHandoffStatus.PREPARING
                && fromMemberId.equals(confirmedByMemberId);
    }

    private boolean canAccept(UUID confirmedByMemberId) {
        return status == RoleHandoffStatus.TRANSFERRED
                && toMemberId.equals(confirmedByMemberId);
    }

    private boolean canCancel(UUID confirmedByMemberId) {
        return isOpen()
                && fromMemberId.equals(confirmedByMemberId);
    }

    public boolean isOpen() {
        return status == RoleHandoffStatus.PREPARING
                || status == RoleHandoffStatus.TRANSFERRED;
    }

    public static boolean hasWarnings(
            int activeItemCount,
            int incompleteItemCount,
            int resourceCount
    ) {
        validateSnapshotCounts(activeItemCount, incompleteItemCount, resourceCount);
        return activeItemCount == 0 || incompleteItemCount > 0 || resourceCount == 0;
    }

    private static void validateSnapshotCounts(
            int activeItemCount,
            int incompleteItemCount,
            int resourceCount
    ) {
        if (activeItemCount < 0 || incompleteItemCount < 0 || resourceCount < 0) {
            throw new DomainValidationException("인수인계 스냅샷 개수는 음수일 수 없습니다");
        }
        if (incompleteItemCount > activeItemCount) {
            throw new DomainValidationException("미완료 인수인계 항목 수는 전체 항목 수를 초과할 수 없습니다");
        }
    }

    private static void validateAssignmentRange(
            LocalDate startDate,
            LocalDate endDate,
            String field
    ) {
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new DomainValidationException(field + " 시작일은 종료일보다 늦을 수 없습니다");
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public UUID getSeasonId() {
        return seasonId;
    }

    public UUID getRoleId() {
        return roleId;
    }

    public UUID getFromMemberId() {
        return fromMemberId;
    }

    public UUID getToMemberId() {
        return toMemberId;
    }

    public LocalDate getOutgoingAssignmentStartDate() {
        return outgoingAssignmentStartDate;
    }

    public LocalDate getOutgoingAssignmentEndDate() {
        return outgoingAssignmentEndDate;
    }

    public LocalDate getIncomingAssignmentStartDate() {
        return incomingAssignmentStartDate;
    }

    public LocalDate getIncomingAssignmentEndDate() {
        return incomingAssignmentEndDate;
    }

    public RoleHandoffStatus getStatus() {
        return status;
    }

    public Instant getPreparedAt() {
        return preparedAt;
    }

    public Instant getTransferredAt() {
        return transferredAt;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public UUID getTransferredByMemberId() {
        return transferredByMemberId;
    }

    public UUID getAcceptedByMemberId() {
        return acceptedByMemberId;
    }

    public UUID getCancelledByMemberId() {
        return cancelledByMemberId;
    }

    public Integer getSnapshotItemCount() {
        return snapshotItemCount;
    }

    public Integer getSnapshotIncompleteItemCount() {
        return snapshotIncompleteItemCount;
    }

    public Integer getSnapshotResourceCount() {
        return snapshotResourceCount;
    }

    public boolean isWarningAcknowledged() {
        return warningAcknowledged;
    }

}
