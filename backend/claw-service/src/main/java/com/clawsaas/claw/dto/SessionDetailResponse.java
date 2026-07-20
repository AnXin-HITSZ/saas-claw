package com.clawsaas.claw.dto;

import java.util.List;

public record SessionDetailResponse(
        SessionSummaryResponse meta,
        List<SessionMessageResponse> messages
) {}
