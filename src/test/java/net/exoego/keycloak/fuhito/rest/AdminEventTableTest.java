package net.exoego.keycloak.fuhito.rest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.lang.model.element.Modifier;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Compares the table under "Admin events" in docs/keycloak-integration.md with the writes that the source records.
 * The test parses the source and reads each place that names the operation, the resource type, and the path of a
 * write. These are the calls of {@code recordChange}, each new {@code ChangeRecord}, and each new write of
 * {@code ImportReport.Write}. A value that a method gets from its caller is read from each call in the same file.
 */
class AdminEventTableTest {

    // A value that the source does not fix, such as the ID of a resource.
    private static final String ANY = "{id}";
    private static final Set<String> UNKNOWN = Set.of(ANY);
    private static final Set<String> OPERATIONS = Set.of("CREATE", "UPDATE", "DELETE");
    private static final Pattern TYPE = Pattern.compile("FUHITO_[A-Z_]+");
    private static final Pattern PATH = Pattern.compile("fuhito/[a-z-]+(/\\{id}(/[a-z-]+)?)?");
    private static final Pattern ROW = Pattern.compile("\\| `(FUHITO_[A-Z_]+)` \\|([^|]*)\\|([^|]*)\\|");
    private static final Pattern CODE = Pattern.compile("`([^`]+)`");
    // These pass on what their callers give. The callers and the writes are read instead.
    private static final Set<String> FORWARDERS = Set.of("RequestContext", "Writes.change");

    @Test
    void theTableListsTheOperationsAndPathsThatTheWritesRecord() throws IOException {
        Source source = new Source(Path.of("src/main/java"));
        assertEquals(List.of(), source.problems);

        Map<String, Row> table = table(Path.of("docs/keycloak-integration.md"));
        List<String> differences = new ArrayList<>();
        Set<String> types = new TreeSet<>(table.keySet());
        types.addAll(source.recorded.keySet());
        for (String type : types) {
            if (!Objects.equals(source.recorded.get(type), table.get(type))) {
                differences.add(type + " records " + source.recorded.get(type) + ", the table has "
                        + table.get(type));
            }
        }
        assertEquals(List.of(), differences);
    }

    private record Row(Set<String> operations, Set<String> paths) {
    }

    private static Map<String, Row> table(Path document) throws IOException {
        List<String> lines = Files.readAllLines(document, StandardCharsets.UTF_8);
        Map<String, Row> rows = new TreeMap<>();
        for (String line : lines.subList(lines.indexOf("## Admin events") + 1, lines.size())) {
            if (line.startsWith("## ")) {
                break;
            }
            Matcher row = ROW.matcher(line);
            if (!row.matches()) {
                continue;
            }
            Set<String> paths = new TreeSet<>();
            String first = null;
            for (String code : codes(row.group(3))) {
                // "and the same with `/end`" adds a part to the first path.
                if (code.startsWith("/")) {
                    paths.add(first + code);
                } else {
                    first = code.replaceAll("\\{[a-zA-Z]+}", ANY);
                    paths.add(first);
                }
            }
            rows.put(row.group(1), new Row(new TreeSet<>(codes(row.group(2))), paths));
        }
        return rows;
    }

    private static List<String> codes(String cell) {
        return CODE.matcher(cell).results().map(match -> match.group(1)).toList();
    }

    private static final class Source {

        private final List<String> problems = new ArrayList<>();
        private final Map<String, Row> recorded = new TreeMap<>();
        private final Map<String, TreePath> topClasses = new HashMap<>();
        private final Map<String, TreePath> writes = new HashMap<>();
        private final Map<CompilationUnitTree, List<TreePath>> calls = new HashMap<>();
        private final List<TreePath> sites = new ArrayList<>();
        private Trees trees;
        private int depth;

        Source(Path root) throws IOException {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            try (StandardJavaFileManager files = compiler.getStandardFileManager(null, Locale.ROOT,
                    StandardCharsets.UTF_8);
                    Stream<Path> walk = Files.walk(root)) {
                List<Path> paths = walk.filter(path -> path.toString().endsWith(".java")).toList();
                JavacTask task = (JavacTask) compiler.getTask(null, files, null, List.of("-proc:none"), null,
                        files.getJavaFileObjectsFromPaths(paths));
                trees = Trees.instance(task);
                for (CompilationUnitTree unit : task.parse()) {
                    index(unit);
                }
                for (TreePath site : sites) {
                    read(site);
                }
            }
        }

        private void index(CompilationUnitTree unit) {
            List<TreePath> invocations = new ArrayList<>();
            calls.put(unit, invocations);
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitClass(ClassTree tree, Void unused) {
                    Tree parent = getCurrentPath().getParentPath().getLeaf();
                    if (parent instanceof CompilationUnitTree) {
                        topClasses.put(tree.getSimpleName().toString(), getCurrentPath());
                    } else if (tree.getKind() == Tree.Kind.RECORD && parent instanceof ClassTree outer
                            && outer.getSimpleName().contentEquals("Write")) {
                        writes.put(tree.getSimpleName().toString(), getCurrentPath());
                    }
                    return super.visitClass(tree, null);
                }

                @Override
                public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
                    invocations.add(getCurrentPath());
                    if (name(tree.getMethodSelect()).equals("recordChange")) {
                        sites.add(getCurrentPath());
                    }
                    return super.visitMethodInvocation(tree, null);
                }

                @Override
                public Void visitNewClass(NewClassTree tree, Void unused) {
                    sites.add(getCurrentPath());
                    return super.visitNewClass(tree, null);
                }
            }.scan(unit, null);
        }

        private void read(TreePath site) {
            TreePath top = site;
            while (!(top.getParentPath().getLeaf() instanceof CompilationUnitTree)) {
                top = top.getParentPath();
            }
            String owner = ((ClassTree) top.getLeaf()).getSimpleName().toString();
            TreePath method = method(site);
            if (FORWARDERS.contains(owner) || (method != null
                    && FORWARDERS.contains(owner + "." + ((MethodTree) method.getLeaf()).getName()))) {
                return;
            }
            List<? extends ExpressionTree> args;
            if (site.getLeaf() instanceof NewClassTree created) {
                String type = name(created.getIdentifier());
                if (writes.containsKey(type)) {
                    envs(site, 0).forEach(env -> write(site, writes.get(type), created.getArguments(), env));
                    return;
                }
                if (!type.equals("ChangeRecord")) {
                    return;
                }
                args = created.getArguments();
            } else {
                args = ((MethodInvocationTree) site.getLeaf()).getArguments();
            }
            for (Map<String, Set<String>> env : envs(site, 0)) {
                record(site, eval(child(site, args.get(0)), env), eval(child(site, args.get(1)), env),
                        eval(child(site, args.get(3)), env));
            }
        }

        private void write(TreePath site, TreePath type, List<? extends ExpressionTree> args,
                Map<String, Set<String>> env) {
            Map<String, Set<String>> fields = components(type, args.stream()
                    .map(arg -> eval(child(site, arg), env))
                    .toList());
            for (Map<String, Set<String>> each : choices(type, fields)) {
                Set<String> operations = new TreeSet<>();
                if (returned(type, "deleted", each).contains("true")) {
                    operations.add("DELETE");
                } else {
                    Set<String> created = returned(type, "created", each);
                    if (!created.contains("false")) {
                        operations.add("CREATE");
                    }
                    if (!created.contains("true")) {
                        operations.add("UPDATE");
                    }
                }
                record(site, operations, returned(type, "resourceType", each), returned(type, "path", each));
            }
        }

        private void record(TreePath site, Set<String> operations, Set<String> types, Set<String> paths) {
            for (String type : types) {
                if (!TYPE.matcher(type).matches()) {
                    problem(site, "the resource type " + type);
                    continue;
                }
                Row row = recorded.computeIfAbsent(type, any -> new Row(new TreeSet<>(), new TreeSet<>()));
                for (String operation : operations) {
                    if (OPERATIONS.contains(operation)) {
                        row.operations().add(operation);
                    } else {
                        problem(site, "the operation " + operation);
                    }
                }
                for (String path : paths) {
                    if (PATH.matcher(path).matches()) {
                        row.paths().add(path);
                    } else {
                        problem(site, "the path " + path);
                    }
                }
            }
        }

        private void problem(TreePath site, String what) {
            CompilationUnitTree unit = site.getCompilationUnit();
            long line = unit.getLineMap().getLineNumber(
                    trees.getSourcePositions().getStartPosition(unit, site.getLeaf()));
            problems.add(Path.of(unit.getSourceFile().toUri()).getFileName() + ":" + line
                    + " records a write whose source does not fix " + what);
        }

        // One set of values of the parameters of the method for each call in the same file, and one with unknown
        // values when another file can call it.
        private List<Map<String, Set<String>>> envs(TreePath at, int level) {
            TreePath method = method(at);
            if (method == null) {
                return List.of(Map.of());
            }
            MethodTree tree = (MethodTree) method.getLeaf();
            List<String> params = tree.getParameters().stream().map(param -> param.getName().toString()).toList();
            List<Map<String, Set<String>>> result = new ArrayList<>();
            boolean callers = !params.isEmpty() && level < 5 && !tree.getName().contentEquals("<init>");
            if (!callers || !tree.getModifiers().getFlags().contains(Modifier.PRIVATE)) {
                Map<String, Set<String>> unknown = new HashMap<>();
                params.forEach(param -> unknown.put(param, UNKNOWN));
                result.add(unknown);
            }
            if (!callers) {
                return result;
            }
            for (TreePath call : calls.get(at.getCompilationUnit())) {
                MethodInvocationTree invocation = (MethodInvocationTree) call.getLeaf();
                if (!(invocation.getMethodSelect() instanceof IdentifierTree name)
                        || !name.getName().contentEquals(tree.getName())
                        || invocation.getArguments().size() != params.size()) {
                    continue;
                }
                for (Map<String, Set<String>> outer : envs(call, level + 1)) {
                    Map<String, Set<String>> env = new HashMap<>();
                    for (int i = 0; i < params.size(); i++) {
                        env.put(params.get(i), eval(child(call, invocation.getArguments().get(i)), outer));
                    }
                    result.add(env);
                }
            }
            return result;
        }

        // The components of a record are kept as "this.<name>", so that a method of the record can read them.
        private Map<String, Set<String>> components(TreePath type, List<Set<String>> args) {
            ClassTree tree = (ClassTree) type.getLeaf();
            List<String> names = tree.getMembers().stream()
                    .filter(member -> member instanceof VariableTree field
                            && !field.getModifiers().getFlags().contains(Modifier.STATIC))
                    .map(member -> ((VariableTree) member).getName().toString())
                    .toList();
            Map<String, Set<String>> fields = new HashMap<>();
            if (args.size() == names.size()) {
                for (int i = 0; i < names.size(); i++) {
                    fields.put("this." + names.get(i), args.get(i));
                }
                return fields;
            }
            for (Tree member : tree.getMembers()) {
                if (member instanceof MethodTree constructor && constructor.getName().contentEquals("<init>")
                        && constructor.getParameters().size() == args.size()
                        && !constructor.getBody().getStatements().isEmpty()
                        && constructor.getBody().getStatements().getFirst() instanceof ExpressionStatementTree first
                        && first.getExpression() instanceof MethodInvocationTree call
                        && name(call.getMethodSelect()).equals("this")) {
                    Map<String, Set<String>> env = new HashMap<>();
                    for (int i = 0; i < args.size(); i++) {
                        env.put(constructor.getParameters().get(i).getName().toString(), args.get(i));
                    }
                    TreePath at = new TreePath(new TreePath(new TreePath(new TreePath(type, constructor),
                            constructor.getBody()), first), call);
                    return components(type, call.getArguments().stream()
                            .map(arg -> eval(child(at, arg), env))
                            .toList());
                }
            }
            names.forEach(name -> fields.put("this." + name, UNKNOWN));
            return fields;
        }

        // A write that switches over an unknown component is read once for each case.
        private List<Map<String, Set<String>>> choices(TreePath type, Map<String, Set<String>> fields) {
            List<SwitchExpressionTree> switches = new ArrayList<>();
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitSwitchExpression(SwitchExpressionTree tree, Void unused) {
                    switches.add(tree);
                    return super.visitSwitchExpression(tree, null);
                }
            }.scan(type, null);
            for (SwitchExpressionTree each : switches) {
                String component = "this." + name(each.getExpression());
                if (fields.getOrDefault(component, Set.of()).contains(ANY)) {
                    return each.getCases().stream()
                            .flatMap(c -> labels(c).stream())
                            .map(label -> {
                                Map<String, Set<String>> copy = new HashMap<>(fields);
                                copy.put(component, Set.of(label));
                                return copy;
                            })
                            .toList();
                }
            }
            return List.of(fields);
        }

        // The value of a method of a write with no parameters, from the record, its component, or the interface.
        private Set<String> returned(TreePath type, String method, Map<String, Set<String>> fields) {
            List<TreePath> found = declared(type, method, 0);
            if (found.isEmpty() && fields.containsKey("this." + method)) {
                return fields.get("this." + method);
            }
            if (found.isEmpty()) {
                found = methods(type, method, 0);
            }
            return values(found, fields, List.of());
        }

        private Set<String> eval(TreePath path, Map<String, Set<String>> env) {
            Tree tree = path.getLeaf();
            return switch (tree) {
                case LiteralTree literal -> Set.of(String.valueOf(literal.getValue()));
                case ParenthesizedTree parens -> eval(child(path, parens.getExpression()), env);
                case BinaryTree plus when plus.getKind() == Tree.Kind.PLUS -> {
                    Set<String> result = new TreeSet<>();
                    for (String left : eval(child(path, plus.getLeftOperand()), env)) {
                        for (String right : eval(child(path, plus.getRightOperand()), env)) {
                            result.add(left + right);
                        }
                    }
                    yield result;
                }
                case ConditionalExpressionTree conditional -> {
                    Set<String> result = new TreeSet<>(eval(child(path, conditional.getTrueExpression()), env));
                    result.addAll(eval(child(path, conditional.getFalseExpression()), env));
                    yield result;
                }
                case IdentifierTree identifier -> resolve(path, identifier.getName().toString(), env);
                case MemberSelectTree select -> constant(select);
                case MethodInvocationTree call -> invoke(path, call, env);
                case SwitchExpressionTree choice -> select(path, choice, env);
                default -> UNKNOWN;
            };
        }

        private Set<String> resolve(TreePath path, String name, Map<String, Set<String>> env) {
            for (TreePath at = path; at != null; at = at.getParentPath()) {
                switch (at.getLeaf()) {
                    case BlockTree block -> {
                        for (Tree statement : block.getStatements()) {
                            if (statement instanceof VariableTree local && local.getName().contentEquals(name)) {
                                return local.getInitializer() == null ? UNKNOWN
                                        : eval(child(child(at, local), local.getInitializer()), env);
                            }
                        }
                    }
                    case LambdaExpressionTree lambda -> {
                        if (lambda.getParameters().stream().anyMatch(param -> param.getName().contentEquals(name))) {
                            return UNKNOWN;
                        }
                    }
                    case EnhancedForLoopTree loop -> {
                        if (loop.getVariable().getName().contentEquals(name)) {
                            return UNKNOWN;
                        }
                    }
                    case MethodTree method -> {
                        if (method.getParameters().stream().anyMatch(param -> param.getName().contentEquals(name))) {
                            return env.getOrDefault(name, UNKNOWN);
                        }
                    }
                    case ClassTree type -> {
                        Set<String> field = field(at, name, env);
                        if (field != null) {
                            return field;
                        }
                    }
                    default -> {
                    }
                }
            }
            return UNKNOWN;
        }

        private Set<String> field(TreePath type, String name, Map<String, Set<String>> env) {
            for (Tree member : ((ClassTree) type.getLeaf()).getMembers()) {
                if (member instanceof VariableTree field && field.getName().contentEquals(name)) {
                    // An enum constant stands for itself.
                    if (field.getInitializer() instanceof NewClassTree) {
                        return Set.of(name);
                    }
                    return field.getInitializer() == null ? env.getOrDefault("this." + name, UNKNOWN)
                            : eval(child(child(type, field), field.getInitializer()), Map.of());
                }
            }
            return null;
        }

        private Set<String> constant(MemberSelectTree select) {
            String name = select.getIdentifier().toString();
            if (select.getExpression() instanceof IdentifierTree owner
                    && topClasses.containsKey(owner.getName().toString())) {
                Set<String> field = field(topClasses.get(owner.getName().toString()), name, Map.of());
                if (field != null) {
                    return field;
                }
            }
            return Set.of(name);
        }

        private Set<String> invoke(TreePath path, MethodInvocationTree call, Map<String, Set<String>> env) {
            if (!(call.getMethodSelect() instanceof IdentifierTree method) || depth > 20) {
                return UNKNOWN;
            }
            List<Set<String>> args = call.getArguments().stream().map(arg -> eval(child(path, arg), env)).toList();
            depth++;
            try {
                return values(methods(path, method.getName().toString(), args.size()), env, args);
            } finally {
                depth--;
            }
        }

        // The values that the methods return, with the arguments as their parameters.
        private Set<String> values(List<TreePath> methods, Map<String, Set<String>> env, List<Set<String>> args) {
            Set<String> result = new TreeSet<>();
            for (TreePath method : methods) {
                MethodTree tree = (MethodTree) method.getLeaf();
                Map<String, Set<String>> inner = new HashMap<>();
                env.forEach((name, value) -> {
                    if (name.startsWith("this.")) {
                        inner.put(name, value);
                    }
                });
                for (int i = 0; i < args.size(); i++) {
                    inner.put(tree.getParameters().get(i).getName().toString(), args.get(i));
                }
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitReturn(ReturnTree ret, Void unused) {
                        result.addAll(ret.getExpression() == null ? UNKNOWN
                                : eval(child(getCurrentPath(), ret.getExpression()), inner));
                        return null;
                    }

                    @Override
                    public Void visitLambdaExpression(LambdaExpressionTree lambda, Void unused) {
                        return null;
                    }

                    @Override
                    public Void visitClass(ClassTree type, Void unused) {
                        return null;
                    }
                }.scan(child(method, tree.getBody()), null);
            }
            return result.isEmpty() ? UNKNOWN : result;
        }

        // The methods of the nearest class around the path that has one of the name and the number of parameters.
        private static List<TreePath> methods(TreePath path, String name, int params) {
            for (TreePath at = path.getParentPath(); at != null; at = at.getParentPath()) {
                if (at.getLeaf() instanceof ClassTree) {
                    List<TreePath> found = declared(at, name, params);
                    if (!found.isEmpty()) {
                        return found;
                    }
                }
            }
            return List.of();
        }

        private static List<TreePath> declared(TreePath type, String name, int params) {
            return ((ClassTree) type.getLeaf()).getMembers().stream()
                    .filter(member -> member instanceof MethodTree method && method.getName().contentEquals(name)
                            && method.getParameters().size() == params && method.getBody() != null)
                    .map(member -> child(type, member))
                    .toList();
        }

        private Set<String> select(TreePath path, SwitchExpressionTree choice, Map<String, Set<String>> env) {
            Set<String> selected = eval(child(path, choice.getExpression()), env);
            List<? extends CaseTree> taken = choice.getCases().stream()
                    .filter(c -> selected.contains(ANY) || labels(c).stream().anyMatch(selected::contains))
                    .toList();
            if (taken.isEmpty()) {
                taken = choice.getCases().stream().filter(c -> labels(c).isEmpty()).toList();
            }
            Set<String> result = new TreeSet<>();
            for (CaseTree c : taken) {
                result.addAll(c.getBody() instanceof ExpressionTree body
                        ? eval(child(child(path, c), body), env) : UNKNOWN);
            }
            return result.isEmpty() ? UNKNOWN : result;
        }

        private static List<String> labels(CaseTree c) {
            return c.getExpressions().stream().map(Source::name).toList();
        }

        private static TreePath method(TreePath path) {
            for (TreePath at = path; at != null; at = at.getParentPath()) {
                if (at.getLeaf() instanceof MethodTree) {
                    return at;
                }
                if (at.getLeaf() instanceof ClassTree) {
                    return null;
                }
            }
            return null;
        }

        private static TreePath child(TreePath parent, Tree tree) {
            return new TreePath(parent, tree);
        }

        private static String name(Tree tree) {
            return switch (tree) {
                case IdentifierTree identifier -> identifier.getName().toString();
                case MemberSelectTree select -> select.getIdentifier().toString();
                case ParameterizedTypeTree type -> name(type.getType());
                case ParenthesizedTree parens -> name(parens.getExpression());
                default -> "";
            };
        }
    }
}
