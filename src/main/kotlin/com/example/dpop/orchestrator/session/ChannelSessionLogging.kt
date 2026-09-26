package com.example.dpop.orchestrator.session

import com.example.dpop.orchestrator.journeytrace.LoggedChannel

/**
 * What the journey trace records about this channel.
 *
 * The conversion lives HERE, with the entity, not in `journeytrace`: the log is a trace and must not
 * depend on the machine it traces (see [LoggedChannel]). Whoever owns the type owns the mapping.
 */
fun ChannelSession.forLog(): LoggedChannel = LoggedChannel(
    channelSessionId = checkNotNull(channelSessionId) { "Channel session without an id cannot be logged" },
    bindingKeyRef = bindingKeyRef,
    channelType = channel?.name,
    accountId = accountId
)
