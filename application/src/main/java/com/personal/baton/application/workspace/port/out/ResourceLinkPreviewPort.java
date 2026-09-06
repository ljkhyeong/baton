package com.personal.baton.application.workspace.port.out;

import com.personal.baton.application.workspace.port.in.ResourceLinkPreviewUseCase.Preview;

public interface ResourceLinkPreviewPort {
    Preview preview(String url);
}
