package com.example.dpop.orchestrator.api.v1

import com.example.dpop.auth_kobil.api.v1.KobilUnlockCredential
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.yaml.snakeyaml.Yaml
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path

/**
 * The discriminator values in the contract must be the ones Jackson actually reads.
 *
 * `JacksonSubTypesModelConverter` derives `discriminator.mapping` from `@JsonSubTypes`, so the two
 * cannot drift by construction - but only as long as that converter runs. If it silently stops
 * working (a swagger-core upgrade, a changed resolve order), the spec falls back to a discriminator
 * without mapping, and a generated client starts sending the SCHEMA NAME as the discriminator
 * value: `kind: "BiometricUnlock"` instead of `kind: "biometric"`. Jackson rejects that, but only
 * at runtime, in whichever client was generated last.
 *
 * So this reads the checked-in contract and feeds every mapping key through Jackson itself. No
 * Spring context: the snapshot is a file, and Jackson needs no server.
 */
class DiscriminatorMappingTest : BehaviorSpec({

    val mapper = JsonMapper.builder().build()

    given("the checked-in contract") {
        then("every discriminator value deserializes to the class it is mapped to") {
            val spec = Yaml().load<Map<*, *>>(Files.readString(SNAPSHOT))
            val schemas = ((spec["components"] as Map<*, *>)["schemas"] as Map<*, *>)

            val unlock = schemas["KobilUnlockCredential"] as Map<*, *>
            val discriminator = unlock["discriminator"] as? Map<*, *>
                ?: error("KobilUnlockCredential hat keinen discriminator mehr - JacksonSubTypesModelConverter?")
            val property = discriminator["propertyName"] as String
            val mapping = discriminator["mapping"] as? Map<*, *>
                ?: error(
                    "discriminator.mapping fehlt. Ein Generator leitet die Werte dann aus den " +
                        "Schemanamen ab und sendet kind=\"BiometricUnlock\" statt \"biometric\"."
                )
            mapping.keys.shouldNotBeEmpty()

            mapping.forEach { (value, ref) ->
                val payload = """{"$property": "$value", "unlockSecret": "s", "password": "p"}"""
                val parsed = mapper.readValue(payload, KobilUnlockCredential::class.java)
                // Das Schema, auf das die Mapping-Zeile zeigt, muss die Klasse sein, die Jackson
                // fuer denselben Wert waehlt.
                parsed.javaClass.simpleName shouldBe (ref as String).substringAfterLast('/')
            }
        }
    }
}) {
    private companion object {
        private val SNAPSHOT: Path = Path.of("api", "openapi.yaml").toAbsolutePath()
    }
}
