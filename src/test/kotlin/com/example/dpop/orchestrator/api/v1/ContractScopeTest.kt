package com.example.dpop.orchestrator.api.v1

import com.example.dpop.tool_api.API_V1
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path

/**
 * Der App-Vertrag enthaelt genau das, was unter [API_V1] liegt - der eingecheckte UND der
 * veroeffentlichte Stand.
 *
 * `ModuleApiGroups` sorgt dafuer beim Erzeugen. Dieser Test prueft das Ergebnis, denn ein
 * Betriebs- oder Mock-Endpunkt, der einmal in `api/published/v1.yaml` steht, laesst sich nur noch
 * als Bruch des App-Vertrags wieder entfernen.
 */
class ContractScopeTest : BehaviorSpec({

    listOf(CONTRACT, PUBLISHED).forEach { file ->
        given(file.fileName.toString()) {
            then("every path lies under $API_V1") {
                @Suppress("UNCHECKED_CAST")
                val paths = (Yaml().load<Map<String, Any?>>(Files.readString(file))["paths"] as Map<String, Any?>).keys
                paths.shouldNotBeEmpty()
                paths.filterNot { it.startsWith("$API_V1/") }.shouldBeEmpty()
            }
        }
    }
}) {
    private companion object {
        private val CONTRACT: Path = Path.of("api", "openapi.yaml").toAbsolutePath()
        private val PUBLISHED: Path = Path.of("api", "published", "v1.yaml").toAbsolutePath()
    }
}
