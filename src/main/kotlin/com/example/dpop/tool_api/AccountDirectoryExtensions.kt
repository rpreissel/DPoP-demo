package com.example.dpop.tool_api

import com.example.dpop.tool_spi.AttributeType

/** The account whose EMAIL anchor is [email], or `null` - the lookup tools' entry point. */
fun AccountDirectory.resolveAccountByEmail(email: String): Long? =
    resolveByAnchor(AttributeType.EMAIL, email)

/** The account bound to register person [personId], or `null` if nobody has claimed them yet. */
fun AccountDirectory.resolveAccountByPersonId(personId: Long): Long? =
    resolveByAnchor(AttributeType.PERSON_ID, personId.toString())

/** KVNR is resolved from current master data, never a locally stored KVNR anchor. */
fun AccountDirectory.resolveAccountByKvnr(kvnr: String, personDirectory: PersonDirectory): Long? =
    personDirectory.findPersonIdByKvnr(normalizeKvnr(kvnr))?.let { resolveAccountByPersonId(it) }
