package com.example.dpop.account

import com.example.dpop.tool_api.PersonDirectory
import com.example.dpop.tool_api.resolveAccountByEmail
import com.example.dpop.tool_api.resolveAccountByKvnr
import com.example.dpop.tool_api.resolveAccountByPersonId

/** Typed lookups share the normalized anchor index, the only place these values are stored. */
fun AccountService.findAccountByEmail(email: String): AccountProfile? =
    resolveAccountByEmail(email)?.let { findAccount(it) }

fun AccountService.findAccountByPersonId(personId: String): AccountProfile? =
    resolveAccountByPersonId(personId)?.let { findAccount(it) }

/** KVNR ownership is current master data, not a historical claim or a local account anchor. */
fun AccountService.findAccountByKvnr(kvnr: String, personDirectory: PersonDirectory): AccountProfile? =
    resolveAccountByKvnr(kvnr, personDirectory)?.let { findAccount(it) }
