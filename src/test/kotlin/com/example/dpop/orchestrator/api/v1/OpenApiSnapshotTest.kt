package com.example.dpop.orchestrator.api.v1

import io.kotest.core.spec.style.BehaviorSpec
import org.springdoc.core.models.GroupedOpenApi
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.client.RestTemplate
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path

/**
 * The API contract has three hand-written readers: the Kotlin DTOs, `frontend/src/types.ts`, and
 * the Java parsing in `keycloak-extension` (`OrchestratorClient`/`OrchestratorNextDispatch`).
 * Nothing used to connect them, so a changed response shape reached each of them as a runtime
 * surprise at a different time. `api/openapi.yaml` is the one place the contract is written down;
 * this test is what keeps it honest - a changed controller or DTO fails HERE, in the diff of a
 * checked-in file, instead of silently in a client.
 *
 * The frontend's generated types are derived from that same snapshot
 * (`frontend/src/generated/api.ts`, `npm run generate:api`), so a contract change the frontend has
 * not caught up with becomes a TypeScript error rather than an `undefined` at runtime.
 *
 * To accept an intended change: `./gradlew updateOpenApiSnapshot`, then `cd frontend && npm run
 * generate:api`, and review both diffs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OpenApiSnapshotTest : BehaviorSpec() {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var groupedApis: List<GroupedOpenApi>

    init {
        given("the running application's springdoc endpoint") {
            then("every group matches its checked-in snapshot under api/") {
                val update = System.getProperty(UPDATE_PROPERTY) == "true"
                val outdated = mutableListOf<String>()

                groups().forEach { group ->
                    val raw = RestTemplate().getForObject("http://localhost:$port/v3/api-docs/$group", String::class.java)
                        ?: error("springdoc lieferte keine Spec fuer die Gruppe '$group'")
                    val live = canonicalize(raw)
                    val file = snapshotFor(group)

                    if (update) {
                        Files.createDirectories(file.parent)
                        Files.writeString(file, live)
                        return@forEach
                    }
                    val stored = if (Files.exists(file)) Files.readString(file) else ""
                    if (live != stored) outdated += "$group -> $file"
                }

                if (update) {
                    println("OpenAPI-Snapshots aktualisiert unter ${SNAPSHOT.parent}")
                    return@then
                }
                if (outdated.isNotEmpty()) {
                    throw AssertionError(
                        """
                        Der API-Vertrag hat sich geaendert, diese Snapshots sind nicht nachgezogen:
                        ${outdated.joinToString("\n                        ")}

                        Beabsichtigt? Dann:
                          ./gradlew updateOpenApiSnapshot
                          ./gradlew generateFrontendApiTypes
                        und die Diffs pruefen - keycloak-extension liest denselben Vertrag von Hand.
                        """.trimIndent()
                    )
                }
            }
        }
    }

    /**
     * The groups actually registered, taken from the beans themselves rather than rebuilt here.
     * A module that gains or loses its controllers is therefore picked up without this test
     * knowing anything about modules (see `ModuleApiGroups`).
     */
    private fun groups(): List<String> = groupedApis.map { it.group }.sorted()

    /**
     * The combined group is the contract as a whole and keeps the plain name; it is what the code
     * generator reads. Per-module files sit in `api/modules/` - one file per module makes a change
     * to one module's endpoints a diff in one small file instead of somewhere inside the contract
     * of all of them.
     */
    private fun snapshotFor(group: String) =
        if (group == COMBINED_GROUP) SNAPSHOT else SNAPSHOT.parent.resolve("modules").resolve("$group.yaml")

    /**
     * YAML rather than JSON, because this file is read by people in diffs: no quoting, no braces,
     * and long descriptions wrap as block text instead of running off as one escaped line.
     *
     * Keys are sorted recursively. springdoc builds its spec from hash-ordered maps, so without
     * this the snapshot would differ between two runs of unchanged code and the check would be
     * useless. Sorting also keeps a diff limited to what actually changed.
     *
     * `servers` is dropped rather than sorted: springdoc fills it with the address this process
     * happens to listen on, which under `RANDOM_PORT` differs every run. It describes one running
     * instance, not the contract.
     */
    private fun canonicalize(raw: String): String {
        val spec = JsonMapper().readValue(raw, Map::class.java).toMutableMap().apply { remove("servers") }

        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            // Keeps long descriptions readable as wrapped block scalars instead of one endless line.
            isPrettyFlow = true
            width = 120
            indent = 2
        }
        return Yaml(options).dump(sortDeeply(spec)).trimEnd() + "\n"
    }

    /** Sorted maps all the way down; lists keep their order, which is meaningful in OpenAPI. */
    private fun sortDeeply(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries
            .sortedBy { it.key.toString() }
            .associateTo(LinkedHashMap()) { it.key.toString() to sortDeeply(it.value) }
        is List<*> -> value.map { sortDeeply(it) }
        else -> value
    }

    companion object {
        private const val UPDATE_PROPERTY = "openapi.snapshot.update"
        private const val COMBINED_GROUP = com.example.dpop.orchestrator.api.v1.COMBINED_GROUP

        /**
         * Repo-relative, resolved from the module directory the test runs in - `api/` rather than
         * `docs/`, because this is a generated artifact: docs/ stays hand-written prose
         * (AGENTS.md), and a generator's output does not belong in a source of truth a human
         * maintains.
         */
        private val SNAPSHOT: Path = Path.of("api", "openapi.yaml").toAbsolutePath()
    }
}
