package com.example.dpop.tool_api

import com.example.dpop.tool_spi.AttributeType

fun AccountDirectory.resolveAccountByEmail(email: String): Long? =
    resolveByAnchor(AttributeType.EMAIL, email)

fun AccountDirectory.resolveAccountByPersonId(personId: Long): Long? =
    resolveByAnchor(AttributeType.PERSON_ID, personId.toString())

/** The eID card pseudonym an earlier attestation left behind (ADR-19) - how a card recognizes the account it already created. */
fun AccountDirectory.resolveAccountByRestrictedId(restrictedId: String): Long? =
    resolveByAnchor(AttributeType.EID_RESTRICTED_ID, restrictedId)

/** KVNR is resolved from current master data, never a locally stored KVNR anchor. */
fun AccountDirectory.resolveAccountByKvnr(kvnr: String, personDirectory: PersonDirectory): Long? =
    personDirectory.findPersonIdByKvnr(normalizeKvnr(kvnr))?.let { resolveAccountByPersonId(it) }
