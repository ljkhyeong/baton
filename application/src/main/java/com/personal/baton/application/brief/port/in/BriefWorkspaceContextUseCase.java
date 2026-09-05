package com.personal.baton.application.brief.port.in;

import com.personal.baton.application.brief.BriefSourceContext;
import com.personal.baton.application.brief.BriefGenerationReadiness;
import java.util.List;

public interface BriefWorkspaceContextUseCase {
    List<BriefSourceContext> resolveSources(BriefAttentionUseCase.Scope scope, List<BriefSourceContext.Identity> identities);

    BriefGenerationReadiness findGenerationReadiness(BriefAttentionUseCase.Scope scope);
}
