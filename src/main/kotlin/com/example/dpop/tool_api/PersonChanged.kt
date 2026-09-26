package com.example.dpop.tool_api

import com.example.dpop.tool_spi.AttributeType

/**
 * The Personenverzeichnis changed a person (ADR-34) - what a real register's change notification
 * would say: who, which kinds of attributes, and the new identifiers (null = the person has none
 * now, see the roles in ADR-34). [kvnr] and [insuranceNumber] travel
 * along because the account stores them itself (KVNR as a claim, the Versicherungsnummer as an
 * anchor); every other attribute is read live, so only its kind is named, never its value.
 *
 * Here in `tool_api`, next to [PersonDirectory], so the Personenverzeichnis can publish it and the
 * account can listen without either knowing the other.
 */
data class PersonChanged(
    val personId: String,
    val changed: Set<AttributeType>,
    val kvnr: String?,
    val insuranceNumber: String?
)
