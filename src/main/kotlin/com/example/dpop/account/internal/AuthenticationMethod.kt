package com.example.dpop.account.internal

import java.time.Instant

class AuthenticationMethod(
    var method: String? = null,
    var active: Boolean = false,
    var createdAt: Instant? = null,
    var details: java.util.Map<String, Any>? = null
)