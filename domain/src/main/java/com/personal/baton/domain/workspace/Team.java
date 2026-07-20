package com.personal.baton.domain.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "teams")
public class Team {

    @Id
    @Column(nullable = false, columnDefinition = "binary(16)")
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "access_key_hash", nullable = false, length = 64, columnDefinition = "char(64)")
    private String accessKeyHash;

    protected Team() {
    }

    private Team(UUID id, String name, String accessKeyHash) {
        this.id = Objects.requireNonNull(id, "팀 식별자는 필수입니다");
        this.name = DomainAssertions.requiredText(name, "팀 이름", 100);
        this.accessKeyHash = DomainAssertions.requiredText(accessKeyHash, "접근 키 해시", 64);
        if (this.accessKeyHash.length() != 64) {
            throw new DomainValidationException("접근 키 해시는 SHA-256 16진수여야 합니다");
        }
    }

    public static Team create(UUID id, String name, String accessKeyHash) {
        return new Team(id, name, accessKeyHash);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getAccessKeyHash() {
        return accessKeyHash;
    }
}
