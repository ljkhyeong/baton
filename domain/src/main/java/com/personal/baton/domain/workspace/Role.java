package com.personal.baton.domain.workspace;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "roles",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_roles_season_name", columnNames = {"season_id", "name"}),
                @UniqueConstraint(
                        name = "uk_roles_season_previous_role",
                        columnNames = {"season_id", "previous_role_id"}
                )
        }
)
public class Role {

    @Id
    @Column(nullable = false, columnDefinition = "binary(16)")
    private UUID id;

    @Column(name = "team_id", nullable = false, columnDefinition = "binary(16)")
    private UUID teamId;

    @Column(name = "season_id", nullable = false, columnDefinition = "binary(16)")
    private UUID seasonId;

    @Column(name = "previous_role_id", columnDefinition = "binary(16)")
    private UUID previousRoleId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 1000)
    private String purpose;

    @Column(name = "current_member_id", columnDefinition = "binary(16)")
    private UUID currentMemberId;

    @Column(name = "next_member_id", columnDefinition = "binary(16)")
    private UUID nextMemberId;

    @Column(name = "assignment_start_date")
    private LocalDate assignmentStartDate;

    @Column(name = "assignment_end_date")
    private LocalDate assignmentEndDate;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "role_responsibilities", joinColumns = @JoinColumn(name = "role_id"))
    @OrderColumn(name = "sort_order")
    @Column(name = "responsibility", nullable = false, length = 500)
    private List<String> responsibilities = new ArrayList<>();

    @Column(length = 1000)
    private String risk;

    @Version
    @Column(nullable = false)
    private Long version;

    protected Role() {
    }

    private Role(
            UUID id,
            UUID teamId,
            UUID seasonId,
            UUID previousRoleId,
            String name,
            String purpose,
            UUID currentMemberId,
            UUID nextMemberId,
            LocalDate assignmentStartDate,
            LocalDate assignmentEndDate,
            List<String> responsibilities,
            String risk
    ) {
        this.id = Objects.requireNonNull(id, "역할 식별자는 필수입니다");
        this.teamId = Objects.requireNonNull(teamId, "팀 식별자는 필수입니다");
        this.seasonId = Objects.requireNonNull(seasonId, "시즌 식별자는 필수입니다");
        this.previousRoleId = previousRoleId;
        update(
                name,
                purpose,
                currentMemberId,
                nextMemberId,
                assignmentStartDate,
                assignmentEndDate,
                responsibilities,
                risk
        );
    }

    public static Role create(
            UUID id,
            UUID teamId,
            UUID seasonId,
            String name,
            String purpose,
            UUID currentMemberId,
            UUID nextMemberId,
            LocalDate assignmentStartDate,
            LocalDate assignmentEndDate,
            List<String> responsibilities,
            String risk
    ) {
        return new Role(id, teamId, seasonId, null, name, purpose, currentMemberId, nextMemberId,
                assignmentStartDate, assignmentEndDate, responsibilities, risk);
    }

    public Role copyToSeason(UUID id, UUID targetSeasonId) {
        return new Role(
                id,
                teamId,
                targetSeasonId,
                this.id,
                name,
                purpose,
                null,
                null,
                null,
                null,
                responsibilities,
                risk
        );
    }

    private static String normalizeName(String name) {
        return DomainAssertions.requiredText(name, "역할 이름", 100);
    }

    public void update(
            String name,
            String purpose,
            UUID currentMemberId,
            UUID nextMemberId,
            LocalDate assignmentStartDate,
            LocalDate assignmentEndDate,
            List<String> responsibilities,
            String risk
    ) {
        String normalizedName = normalizeName(name);
        String normalizedPurpose = DomainAssertions.requiredText(purpose, "역할 목적", 1000);
        if (assignmentStartDate != null
                && assignmentEndDate != null
                && assignmentStartDate.isAfter(assignmentEndDate)) {
            throw new DomainValidationException("역할 배정 시작일은 종료일보다 늦을 수 없습니다");
        }
        List<String> normalizedResponsibilities = new ArrayList<>();
        if (responsibilities != null) {
            for (String responsibility : responsibilities) {
                normalizedResponsibilities.add(
                        DomainAssertions.requiredText(responsibility, "역할 책임", 500)
                );
            }
        }
        String normalizedRisk = DomainAssertions.optionalText(risk, "업무 주의사항", 1000);

        this.name = normalizedName;
        this.purpose = normalizedPurpose;
        this.currentMemberId = currentMemberId;
        this.nextMemberId = nextMemberId;
        this.assignmentStartDate = assignmentStartDate;
        this.assignmentEndDate = assignmentEndDate;
        this.responsibilities.clear();
        this.responsibilities.addAll(normalizedResponsibilities);
        this.risk = normalizedRisk;
    }

    public void prepareHandoff(UUID toMemberId) {
        UUID validatedToMemberId = Objects.requireNonNull(
                toMemberId,
                "다음 담당자 식별자는 필수입니다"
        );
        if (currentMemberId == null) {
            throw new RoleHandoffTransitionException("현재 담당자가 없는 역할은 인수인계를 준비할 수 없습니다");
        }
        if (currentMemberId.equals(validatedToMemberId)) {
            throw new DomainValidationException("현재 담당자와 다음 담당자는 달라야 합니다");
        }
        if (nextMemberId != null && !nextMemberId.equals(validatedToMemberId)) {
            throw new RoleHandoffTransitionException("역할에 지정된 다음 담당자와 인수인계 대상이 다릅니다");
        }
        nextMemberId = validatedToMemberId;
    }

    public void acceptHandoff(
            UUID expectedFromMemberId,
            UUID toMemberId,
            LocalDate incomingAssignmentStartDate,
            LocalDate incomingAssignmentEndDate
    ) {
        UUID validatedFromMemberId = Objects.requireNonNull(
                expectedFromMemberId,
                "이전 담당자 식별자는 필수입니다"
        );
        UUID validatedToMemberId = Objects.requireNonNull(
                toMemberId,
                "다음 담당자 식별자는 필수입니다"
        );
        LocalDate validatedStartDate = Objects.requireNonNull(
                incomingAssignmentStartDate,
                "다음 담당 시작일은 필수입니다"
        );
        if (!validatedFromMemberId.equals(currentMemberId)
                || !validatedToMemberId.equals(nextMemberId)) {
            throw new RoleHandoffTransitionException("역할의 현재·다음 담당자가 준비된 인수인계와 다릅니다");
        }
        if (incomingAssignmentEndDate != null
                && validatedStartDate.isAfter(incomingAssignmentEndDate)) {
            throw new DomainValidationException("다음 담당 시작일은 종료일보다 늦을 수 없습니다");
        }

        currentMemberId = validatedToMemberId;
        nextMemberId = null;
        assignmentStartDate = validatedStartDate;
        assignmentEndDate = incomingAssignmentEndDate;
    }

    public void cancelHandoff(UUID toMemberId) {
        UUID validatedToMemberId = Objects.requireNonNull(
                toMemberId,
                "다음 담당자 식별자는 필수입니다"
        );
        if (!validatedToMemberId.equals(nextMemberId)) {
            throw new RoleHandoffTransitionException("역할의 다음 담당자가 취소할 인수인계와 다릅니다");
        }
        nextMemberId = null;
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

    public UUID getPreviousRoleId() {
        return previousRoleId;
    }

    public String getName() {
        return name;
    }

    public String getPurpose() {
        return purpose;
    }

    public UUID getCurrentMemberId() {
        return currentMemberId;
    }

    public UUID getNextMemberId() {
        return nextMemberId;
    }

    public LocalDate getAssignmentStartDate() {
        return assignmentStartDate;
    }

    public LocalDate getAssignmentEndDate() {
        return assignmentEndDate;
    }

    public List<String> getResponsibilities() {
        return Collections.unmodifiableList(responsibilities);
    }

    public String getRisk() {
        return risk;
    }
}
