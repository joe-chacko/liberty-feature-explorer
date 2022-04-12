//usr/bin/env java --source 11 $0 "$@"; exit $?
// The above line must come first in this file to allow it to be executed as a script on unix systems.
// For other systems, invoke this file using this command:
//  java --source 11 <name of this file>

/*
 * =============================================================================
 * Copyright (c) 2020 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 * =============================================================================
 */


import java.io.FileInputStream;
import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.Arrays.stream;
import static java.util.Collections.unmodifiableMap;
import static java.util.Comparator.comparing;
import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toUnmodifiableList;

public final class lfe {
    static final Path FEATURES_SUBDIR = Paths.get("lib/features");
    static class MisuseError extends Error { MisuseError(String message) { super(message); }}

    interface Opt<O extends Opt<O>> {
        String name();
        String desc();
        Stream<Opt<O>> implied();
        default void addTo(Set<O> set) { set.add((O)this); implied().forEach(i -> i.addTo(set));}
        default String toArg() { return "--" + name().toLowerCase().replace('_', '-'); }
        /** Provides an indented (possibly multi-line) description suitable for printing on a line by itself */
        default String describe() {
            String separator = toArg().length() < 8 ? "\t" : String.format("%n\t\t");
            return String.format("\t%s%s%s", toArg(), separator, desc())
            + Optional.of(implied().map(Opt::toArg).collect(joining(" and ")))
                    .filter(not(String::isEmpty))
                    .map(s -> " Implies " + s + ".")
                    .orElse("");
        }
    }

    enum Flag implements Opt<Flag> {
        HELP("Print this usage message."),
        DECORATE("Mark features with special qualifiers as follows:"
                + "%n\t\t\tpublic|private|protected - the declared visibility"
                + "%n\t\t\tauto - feature automatically enabled in certain conditions"
                + "%n\t\t\tsuperseded - has been superseded by another feature"
                + "%n\t\t\tsingleton - only one version of this feature can be installed per server"),
        FULL_NAMES("Always use the symbolic name of the feature, even if it has a short name."),
        TAB_DELIMITERS("Suppress headers and use tabs to delimit fields to aid scripting.", DECORATE),
        SHOW_PATHS("Display all matching dependency trees (paths if --tab-delimiters is specified)"),
        SIMPLE_SORT("Sort by full name. Do not categorise by visibility.", FULL_NAMES),
        WARN_MISSING("Warn if any features are referenced but not present."),
        IGNORE_DUPLICATES("Do NOT report duplicate feature attributes (e..g short names)."),
        TERMINATOR("Explicitly terminate the flags so that the following argument is interpreted as a query.") {public String toArg() { return "--"; }},
        NOT_A_FLAG(null),
        UNKNOWN(null);
        final String desc;
        final Flag[] impliedFlags;
        Flag(String desc, Flag...impliedFlags) {
            this.desc = Optional.ofNullable(desc).map(String::format).orElse(null);
            this.impliedFlags = impliedFlags;
        }
        public String desc() { return desc; }
        public Stream<Opt<Flag>> implied() { return stream(impliedFlags); }
        /** Provides a multi-line description of all (public) flags */
        static String describeAll() { return String.format(publicFlags().map(Flag::describe).collect(joining("%n%n", "Flags:%n", ""))); }
        static final Map<String, Flag> argMap = unmodifiableMap(publicFlags().collect(HashMap::new, (m, f) -> m.put(f.toArg(), f), Map::putAll));
        static Stream<Flag> publicFlags() { return Stream.of(Flag.values()).filter(f -> f.desc != null); }
        static Flag fromArg(String arg) { return arg.startsWith("--") ? argMap.getOrDefault(arg, UNKNOWN) : NOT_A_FLAG; }
    }

    final WLP wlp;
    final EnumSet<Flag> flags ;
    final List<List<Pattern>> queries;
    final Comparator<List<Attributes>> pathOrdering;
    final Comparator<Attributes> featureOrdering;

    public static void main(String... args) {
        try {
            new lfe(args).run();
        } catch (MisuseError e) {
            System.err.println("ERROR: " + e.getMessage());
        } catch (Throwable e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }

    static final class ArgParser {
        final EnumSet<Flag> flags = EnumSet.noneOf(Flag.class);
        final List<List<Pattern>> query;
        final String[] args;
        int argIndex;

        ArgParser(String...args) {
            this.args = args;
            this.argIndex = 0;
            parseOptions();
            this.query = parseRemainingArguments();
        }

        void parseOptions() {
            for (; argIndex < args.length; argIndex++) {
                final Flag flag = Flag.fromArg(args[argIndex]);
                switch (flag) {
                    case TERMINATOR: ++argIndex; return;
                    case NOT_A_FLAG: return;
                    case UNKNOWN: throw new MisuseError("unknown flag or option '" + args[argIndex] + "'");
                    default: flag.addTo(flags); break;
                }
            }
        }

        List<List<Pattern>> parseRemainingArguments() {
            return IntStream.range(argIndex, args.length)
                    .peek(i -> argIndex = i)
                    .mapToObj(i -> args[i])
                    .map(ArgParser::parseQuery)
                    .collect(toUnmodifiableList());
        }

        private static List<Pattern> parseQuery(String s) {
            return Stream.of(s.split("/"))
                    .map(lfe::globToRegex)
                    .map(Pattern::compile)
                    .collect(toUnmodifiableList());
        }
    }

    public lfe(String... args) {
        var parser = new ArgParser(args);
        this.flags = parser.flags;
        this.queries = parser.query;
        this.wlp = new WLP();
        this.featureOrdering = flags.contains(Flag.SIMPLE_SORT)
                ? comparing(this::featureName)
                : comparing(Visibility::from).thenComparing(this::featureName);
        this.pathOrdering = Lists.comparingEachElement(featureOrdering);
    }

    static String globToRegex(String glob) {
        try (var scanner = new Scanner(glob)) {
            var sb = new StringBuilder();
            scanner.useDelimiter("((?<=[?*])|(?=[?*]))"); // delimit immediately before and/or after a * or ?
            while (scanner.hasNext()) {
                String token = scanner.next();
                switch (token) {
                    case "?":
                        sb.append(".");
                        break;
                    case "*":
                        sb.append(".*");
                        break;
                    default:
                        sb.append(Pattern.quote(token));
                }
            }
            return sb.toString();
        }
    }

    void run() {
        // some flags need processing up front
        if (flags.contains(Flag.HELP)) { printUsage(); return; }
        if (flags.contains(Flag.WARN_MISSING)) wlp.warnMissingFeatures();

        printHeadersIfNeeded();

        final Consumer<Attributes> printVisibilityHeadings = usingHeadings() ? printVisibilityHeadings() : (f -> {});
        final String initialIndent = usingHeadings() ? "  " : "";

        if (flags.contains(Flag.SHOW_PATHS)) {
            if (flags.contains(Flag.TAB_DELIMITERS)) {
                wlp.findFeaturePaths(queries)
                        .sorted(pathOrdering)
                        .distinct()
                        .forEach(path -> {
                            String indent = path.stream()
                                    .map(this::featureName)
                                    .collect(joining("/"))
                                    .replaceFirst("[^/]*$", "");
                            System.out.println(formatFeature(indent, path.get(path.size() - 1)));
                        });
            } else {
                wlp.findFeaturePaths(queries)
                        .sorted(pathOrdering)
                        .distinct()
                        // collect these into a tree structure
                        .collect(TreeNode<Attributes>::new, TreeNode::addPath, TreeNode::combine)
                        // print the tree in ASCII
                        .traverseDepthFirst(initialIndent, printVisibilityHeadings,
                                prefix -> feature -> System.out.println(formatFeature(prefix, feature)));
            }
        } else {
            wlp.findFeaturePaths(queries)
                    .map(Lists::last)
                    .sorted(featureOrdering)
                    .distinct()
                    .peek(printVisibilityHeadings)
                    .forEach(f -> System.out.println(formatFeature(initialIndent, f)));

        }
    }

    private void printHeadersIfNeeded() {
        if (flags.contains(Flag.DECORATE) && ! flags.contains(Flag.TAB_DELIMITERS)) {
            // print some heading columns first
            System.out.println("# VISIBILITY AUTO SUPERSEDED SINGLETON FEATURE NAME");
            System.out.println("# ========== ==== ========== ========= ============");
        }
    }

    private static Consumer<Attributes> printVisibilityHeadings() {
        // Use a 'holder' to track the previous visibility
        Visibility[] currentVisibility = {null};
        return feature -> {
            Visibility newVis = Visibility.from(feature);
            if (newVis != currentVisibility[0]) {
                // the visibility has changed, so print out a heading
                System.out.printf("[%s FEATURES]%n", newVis);
                currentVisibility[0] = newVis;
            }
        };
    }

    private boolean usingHeadings() { return ! flags.contains(Flag.SIMPLE_SORT) && ! flags.contains(Flag.DECORATE); }

    private static void printUsage() {
        final String cmd = lfe.class.getSimpleName();
        System.out.println("Usage: " + cmd + " [flag [flag ...] [--] <pattern> [pattern [pattern ...]]");
        System.out.println("Prints information about features when run from a Liberty root directory." );
        System.out.println("The patterns are treated as file glob patterns.");
        System.out.println("Asterisks match any text, and question marks match a single character.");
        System.out.println("Slashes can be added to navigate dependency hierarchies.");
        System.out.println("If multiple patterns are given, features matching any pattern are listed.");
        System.out.println();
        System.out.println(Flag.describeAll());
        System.out.println();
        System.out.println("Examples:");
        System.out.println();
        System.out.println("\t" + cmd + " *jms*");
        System.out.println("\t\tList all features with jms in their symbolic name or short name.");
        System.out.println();
        System.out.println("\t" + cmd + " javaee-8.0/*");
        System.out.println("\t\tList all features that javaee-8.0 depends on.");
    }

    static Attributes read(Path p) {
        try (InputStream in = new FileInputStream(p.toFile())) {
            return new Manifest(in).getMainAttributes();
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    String formatFeature(String prefix, Attributes feature) {
        final boolean useTabs = flags.contains(Flag.TAB_DELIMITERS);
        final char DELIM = useTabs ? '\t' : ' ';
        final String visibility = Visibility.from(feature).format(useTabs);
        String indent = useTabs ? "" : "  ";
        final String qualifiers = flags.contains(Flag.DECORATE)
                ? indent
                + visibility
                + DELIM + Key.IBM_PROVISION_CAPABILITY.get(feature).map(s -> "auto").orElse("    ")
                + DELIM + Key.SUBSYSTEM_SYMBOLICNAME.parseValues(feature).findFirst().map(v -> v.getQualifier("superseded")).filter(Boolean::valueOf).map(s -> "superseded").orElse("          ")
                + DELIM + Key.SUBSYSTEM_SYMBOLICNAME.parseValues(feature).findFirst().map(v -> v.getQualifier("singleton")).filter(Boolean::valueOf).map(s -> "singleton").orElse("         ")
                + DELIM
                : "";
        return qualifiers + prefix + featureName(feature);
    }

    String featureName(Attributes feature) { return flags.contains(Flag.FULL_NAMES) ? fullName(feature) : shortName(feature); }

    static String fullName(Attributes feature) {
        return Key.SUBSYSTEM_SYMBOLICNAME
                .parseValues(feature)
                .findFirst()
                .orElseThrow(Error::new)
                .id;
    }

    static String shortName(Attributes feature) { return Key.IBM_SHORTNAME.get(feature).orElseGet(() -> fullName(feature)); }

    static class TreeNode<V> {
        final V value;
        final Map<V, TreeNode<V>> children = new LinkedHashMap<>();
        TreeNode() { this(null); } // root node
        TreeNode(V value) { this.value = value; }
        private TreeNode<V> getChild(V value) { return children.computeIfAbsent(value, TreeNode::new); }

        void addPath(List<V> path) {
            TreeNode<V> n = this;
            for (V elem: path) n = n.getChild(elem);
        }

        void combine(TreeNode<V> that) { throw new UnsupportedOperationException("Parallelism not supported here"); }

        void traverseDepthFirst(String prefix, Consumer<V> rootAction, Function<String, Consumer<V>> action) {
            final Consumer<V> fmt = action.apply(prefix);
            children.values()
                    .stream()
                    .peek(n -> rootAction.accept(n.value))
                    .peek(n -> fmt.accept(n.value))
                    .forEach(n -> n.traverseDepthFirst(prefix, action));
        }

        void traverseDepthFirst(String prefix, Function<String, Consumer<V>> formatter) {
            if (children.isEmpty()) return;
            final Consumer<V> printChild = formatter.apply(prefix + "\u2560\u2550");
            children.values()
                    .stream()
                    .limit(children.size() - 1L)
                    .peek(n -> printChild.accept(n.value))
                    .forEach(n -> n.traverseDepthFirst(prefix + "\u2551 ", formatter));
            // now format the last child: not efficient, but accurate and terse(ish)
            children.values()
                    .stream()
                    .skip(children.size() - 1L)
                    .peek(n -> formatter.apply(prefix + "\u255A\u2550").accept(n.value))
                    .forEach(n -> n.traverseDepthFirst(prefix + "  ", formatter));
        }
    }

    static class ValueElement {
        static final Pattern ATOM_PATTERN = Pattern.compile("(([^\";\\\\]|\\\\.)+|\"([^\\\\\"]|\\\\.)*+\")+");
        final String id;
        private final Map<? extends String, String> qualifiers;

        ValueElement(String text) {
            Matcher m = ATOM_PATTERN.matcher(text);
            if (!m.find()) throw new Error("Unable to parse manifest value into constituent parts: " + text);
            this.id = m.group();
            Map<String, String> map = new TreeMap<>();
            while(m.find(m.end())) {
                String[] parts = m.group().split(":?=", 2);
                if (null != map.put(parts[0].trim(), parts[1].trim().replaceFirst("^\"(.*)\"$", "$1")))
                    // TODO: silence this warning if the ignore-duplicates flag has been passed
                    System.err.printf("WARNING: duplicate metadata key '%s' detected in string '%s'", parts[0], text);
            }
            this.qualifiers = unmodifiableMap(map);
        }

        String getQualifier(String key) { return qualifiers.get(key); }

        public String toString() {
            return String.format("%88s : %s", id, qualifiers);
        }
    }

    @SuppressWarnings("unused")
    enum Key implements Function<Attributes, String>, Predicate<Attributes> {
        CREATED_BY("Created-By"),
        IBM_API_PACKAGE("IBM-API-Package"),
        IBM_API_SERVICE("IBM-API-Service"),
        IBM_APP_FORCERESTART("IBM-App-ForceRestart"),
        IBM_APPLIESTO("IBM-AppliesTo"),
        IBM_FEATURE_VERSION("IBM-Feature-Version"),
        IBM_INSTALL_POLICY("IBM-Install-Policy"),
        IBM_INSTALLTO("IBM-InstallTo"),
        IBM_LICENSE_AGREEMENT("IBM-License-Agreement"),
        IBM_PROCESS_TYPES("IBM-Process-Types"),
        IBM_PRODUCTID("IBM-ProductID"),
        IBM_PROVISION_CAPABILITY("IBM-Provision-Capability"),
        IBM_SPI_PACKAGE("IBM-SPI-Package"),
        IBM_SHORTNAME("IBM-ShortName"),
        IBM_TEST_FEATURE("IBM-Test-Feature"),
        SUBSYSTEM_CATEGORY("Subsystem-Category"),
        SUBSYSTEM_CONTENT("Subsystem-Content"),
        SUBSYSTEM_DESCRIPTION("Subsystem-Description"),
        SUBSYSTEM_ENDPOINT_CONTENT("Subsystem-Endpoint-Content"),
        SUBSYSTEM_ENDPOINT_ICONS("Subsystem-Endpoint-Icons"),
        SUBSYSTEM_ENDPOINT_NAMES("Subsystem-Endpoint-Names"),
        SUBSYSTEM_ENDPOINT_SHORTNAMES("Subsystem-Endpoint-ShortNames"),
        SUBSYSTEM_ENDPOINT_URLS("Subsystem-Endpoint-Urls"),
        SUBSYSTEM_LICENSE("Subsystem-License"),
        SUBSYSTEM_LOCALIZATION("Subsystem-Localization"),
        SUBSYSTEM_MANIFESTVERSION("Subsystem-ManifestVersion"),
        SUBSYSTEM_NAME("Subsystem-Name"),
        SUBSYSTEM_SYMBOLICNAME("Subsystem-SymbolicName"),
        SUBSYSTEM_TYPE("Subsystem-Type"),
        SUBSYSTEM_VENDOR("Subsystem-Vendor"),
        SUBSYSTEM_VERSION("Subsystem-Version"),
        TOOL("Tool"),
        WLP_ACTIVATION_TYPE("WLP-Activation-Type")
        ;
        static final Pattern ELEMENT_PATTERN = Pattern.compile("(([^\",\\\\]|\\\\.)+|\"([^\\\\\"]|\\\\.)*+\")+");
        final Attributes.Name name;
        Key(String name) { this.name = new Attributes.Name(name); }
        boolean isPresent(Attributes feature) {
            return feature.containsKey(name);
        }
        boolean isAbsent(Attributes feature) { return ! isPresent(feature); }
        Optional<String> get(Attributes feature) { return Optional.ofNullable(feature.getValue(name)); }
        Stream<ValueElement> parseValues(Attributes feature) {
            return get(feature)
                    .map(ELEMENT_PATTERN::matcher)
                    .map(Matcher::results)
                    .orElse(Stream.empty())
                    .map(MatchResult::group)
                    .map(ValueElement::new);
        }
        public String apply(Attributes feature) { return feature.getValue(name); }
        public boolean test(Attributes feature) { return isPresent(feature); }
    }

    enum Visibility implements Predicate<Attributes> {
        PUBLIC,
        PROTECTED,
        PRIVATE,
        INSTALL,
        DEFAULT;

        static Visibility from(Attributes feature) {
            return Key.SUBSYSTEM_SYMBOLICNAME.parseValues(feature)
                    .findFirst()
                    .map(v -> v.getQualifier("visibility"))
                    .map(String::toUpperCase)
                    .map(Visibility::valueOf)
                    .orElse(Visibility.DEFAULT);
        }
        String format(boolean tabs) { return String.format((tabs ? "%s" : "%-10s"), name().toLowerCase()); }
        public boolean test(Attributes feature) { return this == from(feature); }
    }

    final class WLP {
        final Path root;
        final Path featureSubdir;
        final Map<String, Attributes> featureMap = new HashMap<>();
        final Map<String, Attributes> shortNames = new HashMap<>();
        final Attributes[] features;
        final Map<Attributes, Integer> featureIndex = new HashMap<>();
        final BitSet[] dependencyMatrix;

        WLP() {
            this.root = Paths.get(".");
            this.featureSubdir = this.root.resolve(FEATURES_SUBDIR);
            // validate directories
            if (! Files.isDirectory(this.root)) throw new Error("Not a valid directory: " + this.root.toFile().getAbsolutePath());
            if (! Files.isDirectory(featureSubdir)) throw new Error("No feature subdirectory found: " + featureSubdir.toFile().getAbsolutePath());
            // parse feature manifests
            try (var paths = Files.list(featureSubdir)){
                paths
                        .filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".mf"))
                        .map(lfe::read)
                        .forEach(f -> {
                            var oldValue = featureMap.put(fullName(f), f);
                            if (null != oldValue && !flags.contains(Flag.IGNORE_DUPLICATES))
                                System.err.println("WARNING: duplicate symbolic name found: " + Key.SUBSYSTEM_SYMBOLICNAME.get(f));
                            Key.IBM_SHORTNAME.get(f)
                                    .map(shortName -> shortNames.put(shortName, f))
                                    .filter(whatever -> !flags.contains(Flag.IGNORE_DUPLICATES))
                                    .ifPresent(shortName -> System.err.println("WARNING: duplicate short name found: " + shortName));
                        });
            } catch (IOException e) {
                throw new IOError(e);
            }
            // sort the features by full name
            this.features = allFeatures().sorted(comparing(lfe::fullName)).toArray(Attributes[]::new);
            // create a reverse look-up table for the array
            for (int i = 0; i < features.length; i++) featureIndex.put(features[i], i);
            // create an initially empty dependency matrix
            this.dependencyMatrix = Stream.generate(() -> new BitSet(features.length)).limit(features.length).toArray(BitSet[]::new);
            // add the dependencies
            allFeatures()
                    .filter(Key.SUBSYSTEM_CONTENT)
                    .forEach(f -> {
                        BitSet dependencies = dependencyMatrix[featureIndex.get(f)];
                        Key.SUBSYSTEM_CONTENT.parseValues(f)
                                .filter(v -> "osgi.subsystem.feature".equals(v.getQualifier("type")))
                                .map(v -> v.id)
                                .map(featureMap::get)
                                .map(featureIndex::get)
                                .filter(Objects::nonNull) // ignore unknown features TODO: try tolerated versions instead
                                .forEach(dependencies::set);
                    });
        }

        void warnMissingFeatures() {
            allFeatures()
                    .filter(Key.SUBSYSTEM_CONTENT)
                    .sorted(comparing(lfe::fullName))
                    .forEach(f -> Key.SUBSYSTEM_CONTENT.parseValues(f)
                            .filter(v -> "osgi.subsystem.feature".equals(v.getQualifier("type")))
                            .map(v -> v.id)
                            .filter(id -> !featureMap.containsKey(id))
                            .forEach(id -> System.err.printf("WARNING: feature '%s' depends on absent feature '%s'. " +
                                            "This dependency will be ignored.%n", fullName(f), id)));
        }

        Stream<Attributes> allFeatures() { return featureMap.values().stream(); }

        Stream<List<Attributes>> findFeaturePaths(List<List<Pattern>> queries) {
            if (queries.isEmpty()) return Stream.empty();
            List<List<Attributes>> results = new ArrayList<>();
            for (List<Pattern> patterns: queries)
                findFeaturePaths(allFeatures(), patterns, 0, new ArrayList<>(), results);
            return results.stream();
        }

        private void findFeaturePaths(Stream<Attributes> searchSpace, List<Pattern> patterns, int index, List<Attributes> path, List<List<Attributes>> results) {
            if (patterns.size() == index) {
                results.add(path);
                return;
            }
            Pattern p = patterns.get(index);
            searchSpace
                    .filter(f -> featureMatchesPattern(f, p))
                    .forEach(f -> findFeaturePaths(dependencies(f), patterns, index + 1, Lists.append(path, f), results));
        }

        Stream<Attributes> dependencies(Attributes rootFeature) {
            return dependencyMatrix[featureIndex.get(rootFeature)].stream().mapToObj(i -> features[i]);
        }
    }

    enum Lists {
        ;
        static <T> T head(List<T> list) { return list.get(0); }
        static <T> List<T> tail(List<T> list) { return list.subList(1, list.size()); }
        @SafeVarargs
        static <T> List<T> append(List<T> list, T...items) { return Stream.concat(list.stream(), Stream.of(items)).collect(toUnmodifiableList()); }
        static <T> T last(List<T> list) { return list.get(list.size() - 1); }

        static <T> Comparator<List<T>> comparingEachElement(Comparator<T> elementOrder) {
            return (l1, l2) -> {
                for (int i = 0; i < Math.min(l1.size(), l2.size()); i++) {
                    int c = elementOrder.compare(l1.get(i), l2.get(i));
                    if (c != 0) return c;
                }
                return l1.size() - l2.size();
            };
        }
    }

    static boolean featureMatchesPattern(Attributes f, Pattern p) {
        return Key.IBM_SHORTNAME.get(f)
                .map(p::matcher)
                .map(Matcher::matches)
                .filter(Boolean::booleanValue) // discard non-matches
                .orElse(Key.SUBSYSTEM_SYMBOLICNAME.parseValues(f)
                        .findFirst()
                        .map(v -> v.id)
                        .map(p::matcher)
                        .map(Matcher::matches)
                        .orElse(false));
    }
}
