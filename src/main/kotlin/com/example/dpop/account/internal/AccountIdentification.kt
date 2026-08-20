package com.example.dpop.account.internal

import java.time.Instant

class AccountIdentification(
    var identificationMethod: String? = null,
    var identificationQuality: String? = null,
    var identifiedAt: Instant? = null,
    var registrationSessionId: String? = null,
    var details: java.util.Map<String, Any>? = null
)