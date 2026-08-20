package com.example.dpop.account.internal

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Table(name = "account")
class Account(
    var personId: Long? = null,
    var createdAt: Instant? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @JdbcTypeCode(SqlTypes.JSON)
    var identifications: MutableList<AccountIdentification> = mutableListOf()

    @JdbcTypeCode(SqlTypes.JSON)
    var authenticationMethods: MutableList<AuthenticationMethod> = mutableListOf()

    fun addIdentification(identification: AccountIdentification) {
        identifications.add(identification)
    }

    fun addAuthenticationMethod(authenticationMethod: AuthenticationMethod) {
        authenticationMethods.add(authenticationMethod)
    }
}