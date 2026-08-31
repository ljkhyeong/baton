package com.personal.baton.adapter.in.web.brief;

import com.personal.baton.application.brief.BriefAttentionPage;
import jakarta.validation.constraints.AssertTrue;

public record BriefAttentionCursorRequest(
        BriefAttentionPage.EventType afterEventType, String afterSourceReference
) {
    @AssertTrue(message = "afterEventType과 afterSourceReference는 함께 제공해야 합니다")
    public boolean isCompleteCursor() {
        return (afterEventType == null) == (afterSourceReference == null);
    }

    public BriefAttentionPage.Cursor toCursor() {
        return afterEventType == null ? null
                : new BriefAttentionPage.Cursor(afterEventType, afterSourceReference);
    }
}
