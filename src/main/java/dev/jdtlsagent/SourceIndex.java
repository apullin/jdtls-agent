package dev.jdtlsagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.tools.Diagnostic;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

final class SourceIndex {
    private final Path project;
    private final List<String> sourceRoots;
    private List<Path> files;
    private final Map<Path, FileFingerprint> fingerprints = new HashMap<>();
    private final Map<Path, FileText> texts = new HashMap<>();
    private final List<SourceSymbol> symbols = new ArrayList<>();
    private final Map<String, List<SourceSymbol>> byQualifiedName = new HashMap<>();
    private final Map<Path, List<SourceSymbol>> methodsByFile = new HashMap<>();

    private SourceIndex(Path project, List<String> sourceRoots, List<Path> files) {
        this.project = project;
        this.sourceRoots = List.copyOf(sourceRoots);
        this.files = new ArrayList<>(files);
    }

    static SourceIndex build(Path project, String sourceRoot, String testSourceRoot) throws IOException {
        List<String> roots = new ArrayList<>();
        roots.add(sourceRoot);
        if (testSourceRoot != null && !testSourceRoot.isBlank()) {
            roots.add(testSourceRoot);
        }
        Path normalizedProject = project.toAbsolutePath().normalize();
        List<Path> files = discoverFiles(normalizedProject, roots);
        SourceIndex index = new SourceIndex(normalizedProject, roots, files);
        index.parseAll();
        return index;
    }

    private static List<Path> discoverFiles(Path project, List<String> roots) throws IOException {
        List<Path> files = new ArrayList<>();
        for (String root : roots) {
            Path dir = project.resolve(root).normalize();
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(dir)) {
                stream.filter(path -> path.getFileName().toString().endsWith(".java"))
                        .sorted()
                        .forEach(path -> files.add(path.toAbsolutePath().normalize()));
            }
        }
        return files;
    }

    int sourceFileCount() {
        return files.size();
    }

    List<Path> files() {
        return files;
    }

    List<SourceSymbol> search(String query, boolean includeTests, int limit) {
        String needle = query.toLowerCase(Locale.ROOT);
        List<SourceSymbol> result = new ArrayList<>();
        for (SourceSymbol symbol : symbols) {
            if (!includeTests && isTestFile(symbol.file())) {
                continue;
            }
            String haystack = (symbol.qualifiedName() + " " + symbol.name()).toLowerCase(Locale.ROOT);
            if (haystack.contains(needle)) {
                result.add(symbol);
            }
        }
        result.sort(Comparator
                .comparing(SourceSymbol::qualifiedName)
                .thenComparing(SourceSymbol::kind)
                .thenComparingInt(SourceSymbol::line));
        if (result.size() > limit) {
            return List.copyOf(result.subList(0, limit));
        }
        return List.copyOf(result);
    }

    List<SourceSymbol> apiSurface(String query, boolean includeTests, int limit) {
        List<SourceSymbol> result = new ArrayList<>();
        for (SourceSymbol symbol : symbols) {
            if (!"method".equals(symbol.kind())) {
                continue;
            }
            if (!includeTests && isTestFile(symbol.file())) {
                continue;
            }
            if (!symbol.owner().equals(query) && !symbol.owner().startsWith(query + ".")) {
                continue;
            }
            if (!symbol.modifiers().contains("public") && !symbol.modifiers().contains("static")) {
                continue;
            }
            result.add(symbol);
        }
        result.sort(Comparator
                .comparing(SourceSymbol::qualifiedName)
                .thenComparingInt(SourceSymbol::line));
        if (result.size() > limit) {
            return List.copyOf(result.subList(0, limit));
        }
        return List.copyOf(result);
    }

    List<SourceSymbol> fieldsInScope(String query, boolean includeTests, int limit) {
        List<SourceSymbol> result = new ArrayList<>();
        for (SourceSymbol symbol : symbols) {
            if (!"field".equals(symbol.kind())) {
                continue;
            }
            if (!includeTests && isTestFile(symbol.file())) {
                continue;
            }
            if (!symbol.owner().equals(query) && !symbol.owner().startsWith(query + ".")) {
                continue;
            }
            result.add(symbol);
        }
        result.sort(Comparator
                .comparing(SourceSymbol::qualifiedName)
                .thenComparingInt(SourceSymbol::line));
        if (result.size() > limit) {
            return List.copyOf(result.subList(0, limit));
        }
        return List.copyOf(result);
    }

    ResolveResult resolve(String query, boolean includeTests) {
        ParsedQuery parsed = ParsedQuery.parse(query);
        List<SourceSymbol> candidates = new ArrayList<>();
        List<SourceSymbol> exact = byQualifiedName.get(parsed.baseName());
        if (exact != null) {
            candidates.addAll(exact);
        }
        if (candidates.isEmpty()) {
            for (SourceSymbol symbol : symbols) {
                if (symbol.qualifiedName().equals(parsed.baseName()) || symbol.qualifiedName().endsWith("." + parsed.baseName()) || symbol.name().equals(parsed.baseName())) {
                    candidates.add(symbol);
                }
            }
        }
        if (!includeTests) {
            candidates.removeIf(symbol -> isTestFile(symbol.file()));
        }
        if (parsed.parameterTypes() != null) {
            candidates.removeIf(symbol -> !parametersMatch(symbol.parameterTypes(), parsed.parameterTypes()));
        }
        candidates.sort(Comparator
                .comparing(SourceSymbol::qualifiedName)
                .thenComparingInt(SourceSymbol::line));
        if (candidates.isEmpty()) {
            return ResolveResult.notFound(query);
        }
        Set<String> uniqueNames = new HashSet<>();
        for (SourceSymbol candidate : candidates) {
            uniqueNames.add(candidate.displayName() + "@" + candidate.file() + ":" + candidate.line());
        }
        if (uniqueNames.size() > 1) {
            return ResolveResult.ambiguous(query, candidates);
        }
        return ResolveResult.found(candidates.get(0));
    }

    Optional<SourceSymbol> enclosingMethod(Path file, int offset) {
        Path normalized = file.toAbsolutePath().normalize();
        List<SourceSymbol> methods = methodsByFile.get(normalized);
        if (methods == null) {
            return Optional.empty();
        }
        SourceSymbol best = null;
        int bestSize = Integer.MAX_VALUE;
        for (SourceSymbol method : methods) {
            int start = method.bodyStartOffset() >= 0 ? method.bodyStartOffset() : method.nameOffset();
            int end = method.bodyEndOffset();
            if (end < start) {
                continue;
            }
            if (offset >= start && offset <= end) {
                int size = end - start;
                if (size < bestSize) {
                    best = method;
                    bestSize = size;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    String preview(Path file, int line) {
        try {
            FileText text = text(file);
            if (line <= 0 || line > text.lines().size()) {
                return "";
            }
            return text.lines().get(line - 1).strip();
        } catch (IOException e) {
            return "";
        }
    }

    int offset(Path file, int oneBasedLine, int oneBasedColumn) {
        try {
            FileText text = text(file);
            return text.offset(oneBasedLine, oneBasedColumn);
        } catch (IOException e) {
            return -1;
        }
    }

    boolean isWriteReference(Path file, int offset) {
        // JDTLS gives exact field references; this parser only classifies write context.
        if (offset < 0) {
            return false;
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return false;
        }
        Path normalized = file.toAbsolutePath().normalize();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> objects = manager.getJavaFileObjects(normalized.toFile());
            JavacTask task = (JavacTask) compiler.getTask(null, manager, ignored -> { }, List.of("-proc:none", "-Xlint:-options", "-source", "8"), null, objects);
            Iterable<? extends CompilationUnitTree> parsed = task.parse();
            Trees trees = Trees.instance(task);
            SourcePositions positions = trees.getSourcePositions();
            for (CompilationUnitTree unit : parsed) {
                WriteReferenceScanner scanner = new WriteReferenceScanner(unit, positions, offset);
                scanner.scan(unit, null);
                if (scanner.write) {
                    return true;
                }
            }
        } catch (RuntimeException | IOException e) {
            return false;
        }
        return false;
    }


    boolean isTestFile(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        for (String root : sourceRoots) {
            if (root.toLowerCase(Locale.ROOT).contains("test")) {
                Path testRoot = project.resolve(root).toAbsolutePath().normalize();
                if (normalized.startsWith(testRoot)) {
                    return true;
                }
            }
        }
        return false;
    }

    JsonArray symbolsToJson(List<SourceSymbol> sourceSymbols) {
        JsonArray array = Jsons.array();
        for (SourceSymbol symbol : sourceSymbols) {
            array.add(symbol.toJson());
        }
        return array;
    }

    private void parseAll() {
        rebuild(files);
    }

    RefreshResult refreshIfChanged() throws IOException {
        // Rebuild only when the source set or file fingerprints changed since startup.
        List<Path> discovered = discoverFiles(project, sourceRoots);
        Map<Path, FileFingerprint> current = fingerprintsFor(discovered);
        if (files.equals(discovered) && fingerprints.equals(current)) {
            return new RefreshResult(false, files.size(), symbols.size());
        }
        rebuild(discovered);
        return new RefreshResult(true, files.size(), symbols.size());
    }

    private void rebuild(List<Path> discovered) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No system Java compiler is available; run jdtls-agent with a JDK, not a JRE");
        }
        files = new ArrayList<>(discovered);
        texts.clear();
        symbols.clear();
        byQualifiedName.clear();
        methodsByFile.clear();
        fingerprints.clear();
        fingerprints.putAll(fingerprintsFor(files));
        for (Path file : files) {
            try {
                parseFile(compiler, file);
            } catch (RuntimeException | IOException e) {
                // A partial source index is still useful; jdtls owns semantic correctness for queries.
            }
        }
        for (SourceSymbol symbol : symbols) {
            byQualifiedName.computeIfAbsent(symbol.qualifiedName(), ignored -> new ArrayList<>()).add(symbol);
            if ("method".equals(symbol.kind())) {
                methodsByFile.computeIfAbsent(symbol.file(), ignored -> new ArrayList<>()).add(symbol);
            }
        }
    }

    private static Map<Path, FileFingerprint> fingerprintsFor(List<Path> files) {
        Map<Path, FileFingerprint> result = new HashMap<>();
        for (Path file : files) {
            try {
                result.put(file, FileFingerprint.of(file));
            } catch (IOException ignored) {
            }
        }
        return result;
    }

    private void parseFile(JavaCompiler compiler, Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        texts.put(file.toAbsolutePath().normalize(), new FileText(content));
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> objects = manager.getJavaFileObjects(file.toFile());
            JavacTask task = (JavacTask) compiler.getTask(null, manager, ignored -> { }, List.of("-proc:none", "-Xlint:-options", "-source", "8"), null, objects);
            Iterable<? extends CompilationUnitTree> parsed = task.parse();
            Trees trees = Trees.instance(task);
            SourcePositions positions = trees.getSourcePositions();
            for (CompilationUnitTree unit : parsed) {
                String packageName = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
                new Scanner(file.toAbsolutePath().normalize(), content, unit, positions, packageName).scan(unit, null);
            }
        }
    }

    private FileText text(Path file) throws IOException {
        Path normalized = file.toAbsolutePath().normalize();
        FileText cached = texts.get(normalized);
        if (cached != null) {
            return cached;
        }
        FileText loaded = new FileText(Files.readString(normalized, StandardCharsets.UTF_8));
        texts.put(normalized, loaded);
        return loaded;
    }

    private static boolean parametersMatch(List<String> actual, List<String> expected) {
        if (actual.size() != expected.size()) {
            return false;
        }
        for (int i = 0; i < actual.size(); i++) {
            String a = normalizeType(actual.get(i));
            String e = normalizeType(expected.get(i));
            if (!a.equals(e) && !simpleType(a).equals(simpleType(e))) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeType(String type) {
        return type.replace(" ", "").replace("...", "[]");
    }

    private static String simpleType(String type) {
        int dot = type.lastIndexOf('.');
        return dot >= 0 ? type.substring(dot + 1) : type;
    }

    private static final class WriteReferenceScanner extends TreePathScanner<Void, Void> {
        private final CompilationUnitTree unit;
        private final SourcePositions positions;
        private final int offset;
        private boolean write;

        WriteReferenceScanner(CompilationUnitTree unit, SourcePositions positions, int offset) {
            this.unit = unit;
            this.positions = positions;
            this.offset = offset;
        }

        @Override
        public Void visitAssignment(AssignmentTree node, Void unused) {
            if (contains(node.getVariable())) {
                write = true;
            }
            return super.visitAssignment(node, unused);
        }

        @Override
        public Void visitCompoundAssignment(CompoundAssignmentTree node, Void unused) {
            if (contains(node.getVariable())) {
                write = true;
            }
            return super.visitCompoundAssignment(node, unused);
        }

        @Override
        public Void visitUnary(UnaryTree node, Void unused) {
            Tree.Kind kind = node.getKind();
            if ((kind == Tree.Kind.PREFIX_INCREMENT || kind == Tree.Kind.PREFIX_DECREMENT
                    || kind == Tree.Kind.POSTFIX_INCREMENT || kind == Tree.Kind.POSTFIX_DECREMENT)
                    && contains(node.getExpression())) {
                write = true;
            }
            return super.visitUnary(node, unused);
        }

        private boolean contains(Tree tree) {
            long start = positions.getStartPosition(unit, tree);
            long end = positions.getEndPosition(unit, tree);
            return start <= offset && offset < end;
        }
    }

    private final class Scanner extends TreePathScanner<Void, Void> {
        private final Path file;
        private final String content;
        private final CompilationUnitTree unit;
        private final SourcePositions positions;
        private final String packageName;
        private final ArrayDeque<String> classStack = new ArrayDeque<>();

        Scanner(Path file, String content, CompilationUnitTree unit, SourcePositions positions, String packageName) {
            this.file = file;
            this.content = content;
            this.unit = unit;
            this.positions = positions;
            this.packageName = packageName;
        }

        @Override
        public Void visitClass(ClassTree node, Void unused) {
            String simpleName = node.getSimpleName().toString();
            if (simpleName.isBlank()) {
                return super.visitClass(node, unused);
            }
            String owner = currentOwner();
            String qualifiedName = owner.isEmpty() ? qualify(simpleName) : owner + "." + simpleName;
            int start = safePosition(positions.getStartPosition(unit, node));
            int end = safePosition(positions.getEndPosition(unit, node));
            int nameOffset = findIdentifier(simpleName, start, end);
            SourcePoint point = point(nameOffset);
            symbols.add(new SourceSymbol("class", simpleName, owner.isEmpty() ? packageName : owner, qualifiedName, file, point.line(), point.column(), nameOffset, start, end, List.of(), modifiers(node.getModifiers())));
            classStack.addLast(qualifiedName);
            try {
                return super.visitClass(node, unused);
            } finally {
                classStack.removeLast();
            }
        }

        @Override
        public Void visitMethod(MethodTree node, Void unused) {
            String name = node.getName().toString();
            if ("<init>".equals(name)) {
                name = classStack.isEmpty() ? name : simpleName(classStack.getLast());
            }
            String owner = currentOwner();
            String qualifiedName = owner.isEmpty() ? qualify(name) : owner + "." + name;
            int start = safePosition(positions.getStartPosition(unit, node));
            int end = safePosition(positions.getEndPosition(unit, node));
            int bodyStart = node.getBody() == null ? -1 : safePosition(positions.getStartPosition(unit, node.getBody()));
            int bodyEnd = node.getBody() == null ? end : safePosition(positions.getEndPosition(unit, node.getBody()));
            int searchEnd = bodyStart >= 0 ? bodyStart : end;
            int nameOffset = findIdentifier(name, start, searchEnd);
            SourcePoint point = point(nameOffset);
            List<String> parameterTypes = new ArrayList<>();
            for (VariableTree parameter : node.getParameters()) {
                parameterTypes.add(parameter.getType() == null ? "" : parameter.getType().toString());
            }
            symbols.add(new SourceSymbol("method", name, owner, qualifiedName, file, point.line(), point.column(), nameOffset, bodyStart, bodyEnd, List.copyOf(parameterTypes), modifiers(node.getModifiers())));
            return super.visitMethod(node, unused);
        }

        @Override
        public Void visitVariable(VariableTree node, Void unused) {
            Tree parent = getCurrentPath().getParentPath() == null ? null : getCurrentPath().getParentPath().getLeaf();
            if (parent instanceof ClassTree) {
                String name = node.getName().toString();
                String owner = currentOwner();
                String qualifiedName = owner.isEmpty() ? qualify(name) : owner + "." + name;
                int start = safePosition(positions.getStartPosition(unit, node));
                int end = safePosition(positions.getEndPosition(unit, node));
                int nameOffset = findIdentifier(name, start, end);
                SourcePoint point = point(nameOffset);
                symbols.add(new SourceSymbol("field", name, owner, qualifiedName, file, point.line(), point.column(), nameOffset, -1, end, List.of(), modifiers(node.getModifiers())));
            }
            return super.visitVariable(node, unused);
        }
        private List<String> modifiers(ModifiersTree modifiers) {
            List<String> result = new ArrayList<>();
            for (javax.lang.model.element.Modifier modifier : modifiers.getFlags()) {
                result.add(modifier.toString());
            }
            return List.copyOf(result);
        }


        private String currentOwner() {
            return classStack.isEmpty() ? "" : classStack.getLast();
        }

        private String qualify(String simpleName) {
            return packageName == null || packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
        }

        private int safePosition(long position) {
            if (position == Diagnostic.NOPOS || position < 0 || position > Integer.MAX_VALUE) {
                return -1;
            }
            return (int) position;
        }

        private int findIdentifier(String identifier, int start, int end) {
            if (start < 0) {
                start = 0;
            }
            if (end < 0 || end > content.length()) {
                end = Math.min(content.length(), start + 512);
            }
            int at = content.indexOf(identifier, start);
            while (at >= 0 && at < end) {
                int before = at - 1;
                int after = at + identifier.length();
                boolean beforeOk = before < 0 || !Character.isJavaIdentifierPart(content.charAt(before));
                boolean afterOk = after >= content.length() || !Character.isJavaIdentifierPart(content.charAt(after));
                if (beforeOk && afterOk) {
                    return at;
                }
                at = content.indexOf(identifier, at + 1);
            }
            return start;
        }

        private SourcePoint point(int offset) {
            if (offset < 0) {
                return new SourcePoint(1, 1);
            }
            long line = unit.getLineMap().getLineNumber(offset);
            long column = unit.getLineMap().getColumnNumber(offset);
            return new SourcePoint((int) line, (int) column);
        }
    }

    record ResolveResult(boolean ok, String error, String query, SourceSymbol symbol, List<SourceSymbol> candidates) {
        static ResolveResult found(SourceSymbol symbol) {
            return new ResolveResult(true, null, null, symbol, List.of());
        }

        static ResolveResult notFound(String query) {
            return new ResolveResult(false, "symbol-not-found", query, null, List.of());
        }

        static ResolveResult ambiguous(String query, List<SourceSymbol> candidates) {
            return new ResolveResult(false, "ambiguous-symbol", query, null, List.copyOf(candidates));
        }

        JsonObject toErrorJson() {
            JsonObject object = Jsons.object();
            Jsons.add(object, "ok", false);
            Jsons.add(object, "error", error);
            Jsons.add(object, "query", query);
            JsonArray array = Jsons.array();
            for (SourceSymbol candidate : candidates) {
                array.add(candidate.toJson());
            }
            object.add("candidates", array);
            return object;
        }
    }

    private record ParsedQuery(String baseName, List<String> parameterTypes) {
        static ParsedQuery parse(String query) {
            int open = query.indexOf('(');
            if (open < 0 || !query.endsWith(")")) {
                return new ParsedQuery(query, null);
            }
            String base = query.substring(0, open);
            String body = query.substring(open + 1, query.length() - 1).trim();
            if (body.isEmpty()) {
                return new ParsedQuery(base, List.of());
            }
            List<String> params = new ArrayList<>();
            int depth = 0;
            int start = 0;
            for (int i = 0; i < body.length(); i++) {
                char ch = body.charAt(i);
                if (ch == '<') {
                    depth++;
                } else if (ch == '>') {
                    depth--;
                } else if (ch == ',' && depth == 0) {
                    params.add(body.substring(start, i).trim());
                    start = i + 1;
                }
            }
            params.add(body.substring(start).trim());
            return new ParsedQuery(base, List.copyOf(params));
        }
    }

    record RefreshResult(boolean changed, int sourceFiles, int symbols) {
        JsonObject toJson() {
            JsonObject object = Jsons.object();
            Jsons.add(object, "changed", changed);
            Jsons.add(object, "sourceFiles", sourceFiles);
            Jsons.add(object, "symbols", symbols);
            return object;
        }
    }

    private record FileFingerprint(long size, long modifiedMillis) {
        static FileFingerprint of(Path path) throws IOException {
            return new FileFingerprint(Files.size(path), Files.getLastModifiedTime(path).toMillis());
        }
    }

    private record FileText(String content, List<String> lines, int[] lineStarts) {
        FileText(String content) {
            this(content, splitLines(content), computeLineStarts(content));
        }

        int offset(int oneBasedLine, int oneBasedColumn) {
            if (oneBasedLine <= 0 || oneBasedLine > lineStarts.length) {
                return -1;
            }
            int start = lineStarts[oneBasedLine - 1];
            return Math.min(content.length(), start + Math.max(0, oneBasedColumn - 1));
        }

        private static List<String> splitLines(String content) {
            List<String> result = new ArrayList<>();
            Collections.addAll(result, content.split("\\R", -1));
            return result;
        }

        private static int[] computeLineStarts(String content) {
            List<Integer> starts = new ArrayList<>();
            starts.add(0);
            for (int i = 0; i < content.length(); i++) {
                char ch = content.charAt(i);
                if (ch == '\n') {
                    starts.add(i + 1);
                }
            }
            int[] array = new int[starts.size()];
            for (int i = 0; i < starts.size(); i++) {
                array[i] = starts.get(i);
            }
            return array;
        }
    }

    private record SourcePoint(int line, int column) {
    }

    private static String simpleName(String qualifiedName) {
        int dot = qualifiedName.lastIndexOf('.');
        return dot < 0 ? qualifiedName : qualifiedName.substring(dot + 1);
    }
}
