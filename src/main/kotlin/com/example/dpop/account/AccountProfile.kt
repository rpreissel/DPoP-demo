package com.example.dpop.account

@JvmRecord
data class AccountProfile(
    val accountId: Long?,
    val personId: Long?,
    val activeAuthenticationMethods: List<String>
)