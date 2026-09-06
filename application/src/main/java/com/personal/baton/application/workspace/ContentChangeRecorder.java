package com.personal.baton.application.workspace;

import com.personal.baton.application.identity.port.out.CurrentAccountProvider;
import com.personal.baton.application.identity.port.out.IdentityRepository;
import com.personal.baton.application.workspace.port.out.ContentChangeRepository;
import com.personal.baton.application.workspace.port.out.WorkspacePeopleRepository;
import com.personal.baton.domain.workspace.ContentChange;
import com.personal.baton.domain.workspace.ContentFieldChange;
import com.personal.baton.domain.workspace.ContentRecordKind;
import com.personal.baton.domain.workspace.Decision;
import com.personal.baton.domain.workspace.RoleResource;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class ContentChangeRecorder {
    private final ContentChangeRepository changes;
    private final CurrentAccountProvider current;
    private final IdentityRepository identities;
    private final WorkspacePeopleRepository people;
    private final Clock clock;
    ContentChangeRecorder(ContentChangeRepository changes, CurrentAccountProvider current,
            IdentityRepository identities, WorkspacePeopleRepository people, Clock clock) {
        this.changes = changes; this.current = current; this.identities = identities; this.people = people; this.clock = clock;
    }
    Map<String, String> snapshot(Decision value, String authorName, String roleNames) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("제목", value.getTitle()); fields.put("이유", value.getReason()); fields.put("대안", value.getAlternative());
        fields.put("서식", value.getTextFormat().name().equals("MARKDOWN") ? "Markdown" : "일반 텍스트");
        fields.put("작성자", authorName);
        fields.put("관련 역할", roleNames);
        fields.put("보관 상태", value.getArchivedAt() == null ? "사용 중" : "보관");
        return fields;
    }
    Map<String, String> snapshot(RoleResource value) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("제목", value.getTitle()); fields.put("주소", value.getUrl()); fields.put("설명", value.getDescription());
        fields.put("썸네일", value.getThumbnailUrl());
        fields.put("역할", people.findRoleById(value.getRoleId()).orElseThrow().getName());
        fields.put("보관 상태", value.getArchivedAt() == null ? "사용 중" : "보관");
        return fields;
    }
    void record(UUID teamId, UUID seasonId, ContentRecordKind kind, UUID recordId,
            Map<String, String> before, Map<String, String> after) {
        var fields = after.entrySet().stream().filter(entry -> !Objects.equals(before.get(entry.getKey()), entry.getValue()))
                .map(entry -> new ContentFieldChange(entry.getKey(), before.get(entry.getKey()), entry.getValue())).toList();
        if (fields.isEmpty()) return;
        var actor = current.currentAccountId().flatMap(identities::findAccountById);
        changes.save(ContentChange.create(teamId, seasonId, kind, recordId, actor.map(value -> value.getId()).orElse(null),
                actor.map(value -> value.getDisplayName()).orElse("공유 키 사용자"), clock.instant(), fields));
    }
}
