package com.example.dpop.orchestrator.api.v1;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChannelSessionRequest(
        String channel,
        Map<String, Object> data
) {}
