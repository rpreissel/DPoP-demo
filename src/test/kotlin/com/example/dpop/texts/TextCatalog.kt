package com.example.dpop.texts

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.Frame
import org.objectweb.asm.tree.analysis.SourceInterpreter
import org.objectweb.asm.tree.analysis.SourceValue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readBytes

/** One source wording found in the code, with where it is written. */
data class CatalogEntry(val id: String, val template: String, val bundle: String, val locations: List<String>)

/**
 * Every [Text] template of the application, read from the COMPILED classes rather than the
 * sources: for each `new Text(...)` the analysis traces which value arrives as the template. The
 * compiler has already parsed the code and folded `"a " + "b"` into one constant, so a template
 * is exactly one `LDC` string - anything else (a variable, an interpolation, a call) is a
 * [problems] entry naming class, method and line, and fails [TextCatalogTest].
 *
 * The bundle follows the top-level package: the simulated foreign systems get their own, the rest
 * of the application shares `app` (docs/adr/ADR-033).
 */
class TextCatalog private constructor(val entries: List<CatalogEntry>, val problems: List<String>) {

    /** Bundle name -> id -> entry. */
    val byBundle: Map<String, Map<String, CatalogEntry>> =
        entries.groupBy { it.bundle }.mapValues { (_, list) -> list.associateBy { it.id } }

    val ids: Set<String> = entries.map { it.id }.toSet()

    companion object {
        private const val TEXT = "com/example/dpop/texts/Text"
        private const val ROOT = "com/example/dpop/"

        /** Foreign systems keep their own bundle; everything else is this application's. */
        val FOREIGN_BUNDLES = mapOf("nect_mock" to "nect", "kobil_mock" to "kobil", "ext_personenverzeichnis" to "personenverzeichnis")

        /** The application's own classes, where [Text] itself was loaded from. */
        val application: TextCatalog by lazy {
            of(Path.of(Text::class.java.protectionDomain.codeSource.location.toURI()))
        }

        /**
         * Everything the bundles must word: the backend's templates plus the frontend's own
         * (`frontend/scripts/text-catalog.mjs`, exported by `./gradlew exportFrontendTexts` to the
         * file `texts.frontendCatalog` names). A template both sides use is one entry.
         */
        val all: TextCatalog by lazy {
            val frontend = frontendEntries()
            val merged = (application.entries + frontend)
                .groupBy { it.bundle to it.id }
                .map { (_, same) -> same.first().copy(locations = same.flatMap { it.locations }) }
            TextCatalog(merged, application.problems)
        }

        private fun frontendEntries(): List<CatalogEntry> {
            val file = Path.of(
                System.getProperty("texts.frontendCatalog") ?: error("texts.frontendCatalog not set - run through Gradle")
            )
            check(Files.exists(file)) { "$file missing - ./gradlew exportFrontendTexts" }
            return tools.jackson.module.kotlin.jacksonObjectMapper()
                .readValue(file.toFile(), Array<CatalogEntry>::class.java).toList()
        }

        fun of(classesDir: Path): TextCatalog {
            val found = linkedMapOf<Pair<String, String>, MutableList<String>>()
            val problems = mutableListOf<String>()
            Files.walk(classesDir).use { paths ->
                paths.filter { it.extension == "class" }.sorted().forEach { file ->
                    val node = ClassNode().also { ClassReader(file.readBytes()).accept(it, 0) }
                    if (!node.name.startsWith(ROOT) || node.name == TEXT) return@forEach
                    val bundle = bundleOf(node.name)
                    node.methods.forEach { method -> scan(node, method, bundle, found, problems) }
                }
            }
            val entries = found.map { (key, locations) ->
                val (bundle, template) = key
                CatalogEntry(Text.idOf(template), template, bundle, locations)
            }
            return TextCatalog(entries, problems)
        }

        fun bundleOf(className: String): String =
            FOREIGN_BUNDLES[className.removePrefix(ROOT).substringBefore('/')] ?: "app"

        private fun scan(
            owner: ClassNode,
            method: MethodNode,
            bundle: String,
            found: MutableMap<Pair<String, String>, MutableList<String>>,
            problems: MutableList<String>
        ) {
            val calls = method.instructions.toArray().filterIsInstance<MethodInsnNode>().filter { it.owner == TEXT && it.name == "<init>" }
            if (calls.isEmpty()) return
            val frames = Analyzer(SourceInterpreter()).analyze(owner.name, method)
            calls.forEach { call ->
                val where = "${owner.name.replace('/', '.')}.${method.name}:${lineOf(call)}"
                val frame = frames[method.instructions.indexOf(call)] ?: return@forEach // unreachable code
                val arguments = Type.getArgumentTypes(call.desc)
                val template = constantOf(frame.getStack(frame.stackSize - arguments.size), method, frames)
                if (template != null) {
                    found.getOrPut(bundle to template) { mutableListOf() } += where
                } else {
                    problems += "$where: the template of a Text must be a string literal (placeholders as {name})"
                }
            }
        }

        /**
         * The string constant [value] comes from, or null if it is not one. Follows the compiler's
         * detour through a local variable (it spills the template when the other arguments hold an
         * inline lambda): `LDC; ASTORE n; ...; ALOAD n`.
         */
        private fun constantOf(value: SourceValue, method: MethodNode, frames: Array<Frame<SourceValue>?>, depth: Int = 0): String? {
            val insn = value.insns.singleOrNull() ?: return null
            if (insn is LdcInsnNode) return insn.cst as? String
            if (insn !is VarInsnNode || insn.opcode != Opcodes.ALOAD || depth > 8) return null
            val load = frames[method.instructions.indexOf(insn)] ?: return null
            val store = load.getLocal(insn.`var`).insns.singleOrNull() as? VarInsnNode ?: return null
            val beforeStore = frames[method.instructions.indexOf(store)] ?: return null
            return constantOf(beforeStore.getStack(beforeStore.stackSize - 1), method, frames, depth + 1)
        }

        private fun lineOf(insn: AbstractInsnNode): Int {
            var node: AbstractInsnNode? = insn
            while (node != null) {
                if (node is LineNumberNode) return node.line
                node = node.previous
            }
            return -1
        }
    }
}

/**
 * The source wording behind a text reference as it arrives in a response (`{"key": ...}`) - so an
 * integration test can still say which text it expects without depending on any language's wording.
 */
fun templateOf(reference: Any?): String {
    val key = (reference as? Map<*, *>)?.get("key") ?: error("Not a text reference: $reference")
    return TextCatalog.application.entries.firstOrNull { it.id == key }?.template ?: error("No template with id $key")
}
