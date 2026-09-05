package com.personal.baton.application.brief;

import java.util.UUID;

public record BriefSourceContext(Identity identity, Target target) {
    public record Identity(BriefAttentionPage.EventType eventType, String sourceReference) { }
    public record Target(String title, UUID roleId, UUID routineId, boolean archived) { }
}
