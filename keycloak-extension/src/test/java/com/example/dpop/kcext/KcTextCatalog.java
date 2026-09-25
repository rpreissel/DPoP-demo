package com.example.dpop.kcext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Every own text template of the extension (docs/adr/ADR-033), from two places:
 * <ul>
 *   <li>the compiled classes - each {@code KcText.t(...)} and {@code KcTexts.of(session, ...)} call,
 *   its template traced by ASM's data flow analysis; not a constant string is a problem;</li>
 *   <li>the theme's {@code .ftl} files - each {@code t.of("...")}. FreeMarker has no public
 *   expression tree, so a strict scanner reads them: a {@code t.of(} not followed by a string literal
 *   is a problem, named by file and line;</li>
 *   <li>the Keycloakify theme ({@code keycloak-theme/}) - each {@code t("...")} of its React pages,
 *   read by that package's own parser ({@code npm run texts:export}) into a JSON catalog. Same bundle:
 *   both themes show the same messages files.</li>
 * </ul>
 */
public final class KcTextCatalog {

    public record Entry(String id, String template, List<String> locations) {
    }

    private static final String KC_TEXT = "com/example/dpop/kcext/KcText";
    private static final String KC_TEXTS = "com/example/dpop/kcext/KcTexts";
    private static final Pattern FTL_CALL = Pattern.compile("\\bt\\.of\\(\\s*");
    private static final Pattern FTL_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"|'((?:[^'\\\\]|\\\\.)*)'");

    public final Map<String, Entry> entries = new LinkedHashMap<>();
    public final List<String> problems = new ArrayList<>();

    public static KcTextCatalog of(Path classesDir, Path themeDir) throws IOException {
        KcTextCatalog catalog = new KcTextCatalog();
        try (Stream<Path> files = Files.walk(classesDir)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).sorted().toList()) {
                catalog.scanClass(Files.readAllBytes(file));
            }
        }
        try (Stream<Path> files = Files.walk(themeDir)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".ftl")).sorted().toList()) {
                catalog.scanTemplate(themeDir.relativize(file).toString(), Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        return catalog;
    }

    /** The extension as built: main classes and theme sources, located from the working directory Gradle tests run in. */
    public static KcTextCatalog extension() throws IOException {
        KcTextCatalog catalog = of(Path.of("build/classes/java/main"), Path.of("src/main/resources/theme"));
        catalog.addKeycloakifyCatalog(keycloakifyCatalog());
        return catalog;
    }

    /** Written by {@code :exportKeycloakThemeTexts}; the Gradle test task passes its path. */
    static Path keycloakifyCatalog() {
        return Path.of(System.getProperty("texts.keycloakThemeCatalog", "../keycloak-theme/build/texts-catalog.json"));
    }

    /** The Keycloakify theme's templates - a missing catalog is a problem, not an empty theme. */
    void addKeycloakifyCatalog(Path json) throws IOException {
        if (!Files.exists(json)) {
            problems.add(json + ": Keycloakify text catalog missing - run ./gradlew exportKeycloakThemeTexts");
            return;
        }
        for (JsonNode entry : new ObjectMapper().readTree(json.toFile())) {
            String template = entry.get("template").asText();
            for (JsonNode location : entry.get("locations")) add(template, location.asText());
        }
    }

    private void add(String template, String location) {
        entries.computeIfAbsent(template, t -> new Entry(KcText.idOf(t), t, new ArrayList<>())).locations().add(location);
    }

    void scanClass(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        if (node.name.equals(KC_TEXT) || node.name.equals(KC_TEXTS) || node.name.startsWith(KC_TEXTS + "$")) return;
        for (MethodNode method : node.methods) {
            List<MethodInsnNode> calls = new ArrayList<>();
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                        && ((call.owner.equals(KC_TEXT) && call.name.equals("t")) || (call.owner.equals(KC_TEXTS) && call.name.equals("of")))) {
                    calls.add(call);
                }
            }
            if (calls.isEmpty()) continue;
            Frame<SourceValue>[] frames;
            try {
                frames = new Analyzer<>(new SourceInterpreter()).analyze(node.name, method);
            } catch (Exception e) {
                throw new IllegalStateException("Cannot analyze " + node.name + "." + method.name, e);
            }
            for (MethodInsnNode call : calls) {
                String where = node.name.replace('/', '.') + "." + method.name + ":" + lineOf(call);
                Frame<SourceValue> frame = frames[method.instructions.indexOf(call)];
                if (frame == null) continue;
                Type[] arguments = Type.getArgumentTypes(call.desc);
                int templateIndex = call.owner.equals(KC_TEXTS) ? 1 : 0; // of(session, template) / t(template, ...)
                SourceValue value = frame.getStack(frame.getStackSize() - arguments.length + templateIndex);
                String template = constantOf(value, method, frames, 0);
                if (template != null) add(template, where);
                else problems.add(where + ": the template must be a string literal (placeholders as {name})");
            }
        }
    }

    private static String constantOf(SourceValue value, MethodNode method, Frame<SourceValue>[] frames, int depth) {
        if (value.insns.size() != 1) return null;
        AbstractInsnNode insn = value.insns.iterator().next();
        if (insn instanceof LdcInsnNode ldc) return ldc.cst instanceof String s ? s : null;
        if (!(insn instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || depth > 8) return null;
        Frame<SourceValue> atLoad = frames[method.instructions.indexOf(insn)];
        if (atLoad == null || atLoad.getLocal(load.var).insns.size() != 1) return null;
        AbstractInsnNode store = atLoad.getLocal(load.var).insns.iterator().next();
        Frame<SourceValue> beforeStore = frames[method.instructions.indexOf(store)];
        if (beforeStore == null) return null;
        return constantOf(beforeStore.getStack(beforeStore.getStackSize() - 1), method, frames, depth + 1);
    }

    private static int lineOf(AbstractInsnNode insn) {
        for (AbstractInsnNode n = insn; n != null; n = n.getPrevious()) {
            if (n instanceof LineNumberNode line) return line.line;
        }
        return -1;
    }

    void scanTemplate(String file, String source) {
        // Comments go, their line breaks stay - so line numbers still match the file.
        String withoutComments = Pattern.compile("(?s)<#--.*?-->").matcher(source)
                .replaceAll(m -> "\n".repeat((int) m.group().chars().filter(c -> c == '\n').count()));
        Matcher call = FTL_CALL.matcher(withoutComments);
        while (call.find()) {
            int line = 1 + (int) withoutComments.substring(0, call.start()).chars().filter(c -> c == '\n').count();
            Matcher literal = FTL_LITERAL.matcher(withoutComments).region(call.end(), withoutComments.length());
            if (literal.lookingAt()) {
                String raw = literal.group(1) != null ? literal.group(1) : literal.group(2);
                add(raw.replaceAll("\\\\(.)", "$1"), "keycloak-extension/…/theme/" + file + ":" + line);
            } else {
                problems.add(file + ":" + line + ": t.of(...) needs a string literal (placeholders as {name})");
            }
        }
    }

    /** {@code ./gradlew exportTexts}: args = classes dir, theme dir, output root (writes keycloak/texts_source.properties). */
    public static void main(String[] args) throws IOException {
        KcTextCatalog catalog = of(Path.of(args[0]), Path.of(args[1]));
        catalog.addKeycloakifyCatalog(keycloakifyCatalog());
        if (!catalog.problems.isEmpty()) throw new IllegalStateException(String.join("\n", catalog.problems));
        Path out = Path.of(args[2], "keycloak", "texts_source.properties");
        Files.createDirectories(out.getParent());
        StringBuilder text = new StringBuilder("# Quelle: Vorlagen der keycloak-extension - generiert, nicht bearbeiten.\n");
        catalog.entries.values().stream().sorted((a, b) -> a.id().compareTo(b.id())).forEach(e -> {
            e.locations().forEach(l -> text.append("# ").append(l).append('\n'));
            text.append(e.id()).append('=').append(e.template().replace("\\", "\\\\").replace("\n", "\\n")).append('\n');
        });
        Files.writeString(out, text.toString(), StandardCharsets.UTF_8);
        System.out.println(catalog.entries.size() + " Texte -> " + out);
    }
}
