package com.example.dpop.tool_api

/**
 * The Freischaltcode check of the person register (ADR-31): what an identification tool asks when
 * someone types the code from their letter. The register issued the code, so it is the one to say
 * whether it is valid. Only the digest travels - the tool never keeps the plaintext.
 */
interface ActivationCodes {
    /** The one definition of how a typed code is digested before it is staged or checked. */
    fun digest(code: String): String

    /** Whether [codeDigest] (see [digest]) is a currently valid code of [personId]. */
    fun isValid(personId: String, codeDigest: String): Boolean
}
