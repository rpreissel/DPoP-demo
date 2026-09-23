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
 * The API contract used to have three hand-written readers: the Kotlin DTOs,
 * `frontend/src/types.ts`, and the Java parsing in `keycloak-extension`. Nothing connected them,
 * so a changed response shape reached each of them as a runtime surprise at a different time.
 * `api/openapi.yaml` is now the one place the contract is written down, produced from the running
 * code by this test - a changed controller or DTO fails HERE, in the diff of a checked-in file,
 * instead of silently in a client.
 *
 * Both clients are generated from that same snapshot: the frontend's types into
 * `frontend/src/generated` (`./gradlew generateFrontendApiTypes`, checked in) and the extension's
 * Java models at build time (`:keycloak-extension:generateOrchestratorModels`). A contract change a
 * client has not caught up with becomes a compile error rather than an `undefined` at runtime.
 *
 * To accept an intended change: `./gradlew updateOpenApiSnapshot`, then
 * `./gradlew generateFrontendApiTypes`, and review both diffs (docs/adr/ADR-026).
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
                val specs = groups().associateWith { fetch(it) }
                val shared = sharedSchemas(specs)

                specs.forEach { (group, spec) ->
                    val live = render(if (group == CONTRACT_GROUP) spec else referenceShared(spec, shared))
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
                        und die Diffs pruefen - keycloak-extension erzeugt ihre Modelle beim Build aus demselben Vertrag.
                        """.trimIndent()
                    )
                }
            }

            then("no endpoint claims the caller supplies its own bindingKeyRef") {
                // @BindingKey is filled by DpopBindingKeyResolver from the DPoP proof header or the
                // peer-auth assertion - never from the URL. springdoc does not know about argument
                // resolvers and used to document it as a required query parameter on 74 paths, telling
                // every client to put its own binding key in the URL. BindingKeyOpenApiConfig stops
                // that; this keeps a newly added controller from reintroducing it.
                val spec = render(fetch(CONTRACT_GROUP))
                if (spec.contains("name: bindingKeyRef")) {
                    throw AssertionError(
                        "bindingKeyRef steht wieder als Parameter in der Spec. Er kommt aus dem " +
                            "DPoP-Header, nicht aus der URL - siehe BindingKeyOpenApiConfig."
                    )
                }
                // And the endpoints must still say how a caller authenticates at all, otherwise
                // removing the parameter would only replace one untruth with another.
                if (!spec.contains(BindingKeyOpenApiConfig.PEER_AUTH_SCHEME)) {
                    throw AssertionError("Kein Endpunkt deklariert mehr die Peer-Auth-Alternative.")
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
        if (group == CONTRACT_GROUP) SNAPSHOT else SNAPSHOT.parent.resolve("modules").resolve("$group.yaml")

    /**
     * The group's spec as springdoc serves it, minus `servers`: springdoc fills that with the
     * address this process happens to listen on, which under `RANDOM_PORT` differs every run. It
     * describes one running instance, not the contract.
     */
    private fun fetch(group: String): Map<*, *> {
        val raw = RestTemplate().getForObject("http://localhost:$port/v3/api-docs/$group", String::class.java)
            ?: error("springdoc lieferte keine Spec fuer die Gruppe '$group'")
        return JsonMapper().readValue(raw, Map::class.java).toMutableMap().apply { remove("servers") }
    }

    private fun schemasOf(spec: Map<*, *>): Map<*, *> =
        ((spec["components"] as? Map<*, *>)?.get("schemas") as? Map<*, *>) ?: emptyMap<String, Any?>()

    /**
     * The schemas more than one module uses and the contract holds - the ones a module file
     * references instead of repeating.
     *
     * Every tool endpoint answers with the shared envelope, so as a standalone document each module
     * file used to carry `ChannelResponse` and everything it reaches - some 360 lines, identical in
     * nine files. A change to the envelope showed up as ten diffs, and the few lines a module
     * actually owns were hard to find between them.
     *
     * Derived, not listed. A schema only one module uses stays in that module's file, even though
     * the contract has it too: that is what the file is for. One the contract lacks (admin or mock
     * endpoints only) stays wherever it is used, since there is nothing to point at.
     *
     * `api/openapi.yaml` stays a single file on purpose. A modular contract with the modules as
     * `$ref` targets was tried: under OpenAPI 3.1 swagger-parser inlines external references, and
     * both generators lose every model name (`CreateChannel201ResponseStepDataOneOf3KindEnum`) and
     * the discriminator mapping with them.
     */
    private fun sharedSchemas(specs: Map<String, Map<*, *>>): Set<String> {
        val contract = schemasOf(specs.getValue(CONTRACT_GROUP)).keys.map { it.toString() }.toSet()
        return specs.filterKeys { it != CONTRACT_GROUP }.values
            .flatMap { spec -> schemasOf(spec).keys.map { it.toString() } }
            .groupingBy { it }.eachCount()
            .filter { (name, users) -> users > 1 && name in contract }
            .keys
    }

    /**
     * Drops the [shared] schemas from a module's spec and points its references at
     * `../openapi.yaml` instead. The contract's version is the one referenced - for `StepData` that
     * is the full union rather than the shapes this module can produce, which is the price of not
     * repeating it.
     */
    private fun referenceShared(spec: Map<*, *>, shared: Set<String>): Map<*, *> {
        val kept = schemasOf(spec).filterKeys { it.toString() !in shared }
        val components = (spec["components"] as? Map<*, *>)?.toMutableMap()?.apply {
            if (kept.isEmpty()) remove("schemas") else put("schemas", kept)
        }
        val trimmed = spec.toMutableMap().apply { if (components != null) put("components", components) }
        return rewriteRefs(trimmed, shared) as Map<*, *>
    }

    /** `$ref`s and discriminator mappings alike: both are plain strings naming a local schema. */
    private fun rewriteRefs(value: Any?, shared: Set<String>): Any? = when (value) {
        is Map<*, *> -> value.mapValues { rewriteRefs(it.value, shared) }
        is List<*> -> value.map { rewriteRefs(it, shared) }
        is String ->
            if (value.startsWith(LOCAL_SCHEMA_REF) && value.removePrefix(LOCAL_SCHEMA_REF) in shared) {
                CONTRACT_SCHEMA_REF + value.removePrefix(LOCAL_SCHEMA_REF)
            } else {
                value
            }
        else -> value
    }

    /**
     * YAML rather than JSON, because this file is read by people in diffs: no quoting, no braces,
     * and long descriptions wrap as block text instead of running off as one escaped line.
     *
     * Keys are sorted recursively. springdoc builds its spec from hash-ordered maps, so without
     * this the snapshot would differ between two runs of unchanged code and the check would be
     * useless. Sorting also keeps a diff limited to what actually changed.
     */
    private fun render(spec: Map<*, *>): String {
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
        private const val CONTRACT_GROUP = com.example.dpop.orchestrator.api.v1.CONTRACT_GROUP
        private const val LOCAL_SCHEMA_REF = "#/components/schemas/"
        private const val CONTRACT_SCHEMA_REF = "../openapi.yaml#/components/schemas/"

        /**
         * Repo-relative, resolved from the module directory the test runs in - `api/` rather than
         * `docs/`, because this is a generated artifact: docs/ stays hand-written prose
         * (AGENTS.md), and a generator's output does not belong in a source of truth a human
         * maintains.
         */
        private val SNAPSHOT: Path = Path.of("api", "openapi.yaml").toAbsolutePath()
    }
}
