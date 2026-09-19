package com.example.dpop.tool_api

import com.example.dpop.tool_spi.AttributeType

fun AccountDirectory.resolveAccountByEmail(email: String): Long? =
    resolveByAnchor(AttributeType.EMAIL, email)

fun AccountDirectory.resolveAccountByPersonId(personId: Long): Long? =
    resolveByAnchor(AttributeType.PERSON_ID, personId.toString())

/** KVNR is resolved from current master data, never a locally stored KVNR anchor. */
fun AccountDirectory.resolveAccountByKvnr(kvnr: String, personDirectory: PersonDirectory): Long? =
    personDirectory.findPersonIdByKvnr(normalizeKvnr(kvnr))?.let { resolveAccountByPersonId(it) }
