package com.personal.baton.application.brief;

import java.util.List;

public record BriefEditionComparison(
        BriefEditionHistory.Summary from, BriefEditionHistory.Summary to,
        List<BriefEditionSnapshot.Item> added, List<BriefEditionSnapshot.Item> removed,
        List<Change> changed
) {
    public record Change(BriefEditionSnapshot.Item before, BriefEditionSnapshot.Item after) { }
}
