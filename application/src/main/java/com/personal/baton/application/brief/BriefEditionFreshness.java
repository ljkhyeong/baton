package com.personal.baton.application.brief;

import java.time.Instant;
import java.util.UUID;

/** BRIEF가 받은 변경으로 같은 주간을 지금 다시 선정했을 때 저장된 항목과 같은지 판정한 결과다. */
public record BriefEditionFreshness(UUID editionId, Boolean upToDate, Boolean ruleVersionChanged, Instant evaluatedAt) {
}
