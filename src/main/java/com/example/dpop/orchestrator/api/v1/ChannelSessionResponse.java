package com.example.dpop.orchestrator.api.v1;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.example.dpop.orchestrator.session.ChannelState;

import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChannelSessionResponse(
        UUID channelSessionId,
        ChannelState state,
        String currentAcr,
        List<String> currentAmr,
        Boolean stepUpRequired,
        Long accountId
) {}
