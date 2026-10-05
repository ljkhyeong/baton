package com.personal.baton.domain.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.util.Objects;

// 보관할 수 있는 작업 공간 기록이다. 보관된 기록은 보관을 풀기 전까지 수정하지 않는다.
@MappedSuperclass
public abstract class ArchivableRecord {

    @Column(name = "archived_at")
    private Instant archivedAt;

    // 보관 상태가 바뀌었으면 true를 돌려준다. 이미 보관된 기록은 처음 보관 시각을 유지한다.
    public boolean updateArchive(boolean archived, Instant archivedAt) {
        if (archived == isArchived()) {
            return false;
        }
        this.archivedAt = archived ? Objects.requireNonNull(archivedAt, "보관 시각은 필수입니다") : null;
        return true;
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    protected void requireActive(String archivedMessage) {
        if (isArchived()) {
            throw new DomainValidationException(archivedMessage);
        }
    }
}
