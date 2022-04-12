package io.openliberty.tools.lfe;

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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.joining;

public final class Main {
    public static void main(String[] args) {
        try {
            new Main(args).run();
        } catch (MisuseError e) {
            System.err.println("ERROR: " + e.getMessage());
        } catch (Throwable e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }

    static final Path FEATURES_SUBDIR = Paths.get("lib/features");

    final Liberty liberty;
    final EnumSet<Flag> flags ;
    final List<List<QueryElement>> queries;
    final Comparator<List<Attributes>> pathOrdering;
    final Comparator<Attributes> featureOrdering;

    public Main(String... args) {
        var parser = new ArgParser(args);
        this.flags = parser.flags;
        this.queries = parser.query;
        this.liberty = new Liberty();
        this.featureOrdering = flags.contains(Flag.SIMPLE_SORT)
                ? comparing(this::featureName)
                : comparing(Visibility::from).thenComparing(this::featureName);
        this.pathOrdering = Lists.comparingEachElement(featureOrdering);
    }

    void run() {
        // some flags need processing up front
        if (flags.contains(Flag.HELP)) { printUsage(); return; }
        if (flags.contains(Flag.WARN_MISSING)) liberty.warnMissingFeatures();

        printHeadersIfNeeded();

        final Consumer<Attributes> printVisibilityHeadings = usingHeadings() ? printVisibilityHeadings() : (f -> {});
        final String initialIndent = usingHeadings() ? "  " : "";

        if (flags.contains(Flag.SHOW_PATHS)) {
            if (flags.contains(Flag.TAB_DELIMITERS)) {
                liberty.findFeaturePaths(queries)
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
                liberty.findFeaturePaths(queries)
                        .sorted(pathOrdering)
                        .distinct()
                        // collect these into a tree structure
                        .collect(TreeNode<Attributes>::new, TreeNode::addPath, TreeNode::combine)
                        // print the tree in ASCII
                        .traverseDepthFirst(initialIndent, printVisibilityHeadings,
                                prefix -> feature -> System.out.println(formatFeature(prefix, feature)));
            }
        } else {
            liberty.findFeaturePaths(queries)
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
        final String cmd = Main.class.getSimpleName();
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

    final class Liberty {
        final Path root;
        final Path featureSubdir;
        final Map<String, Attributes> featureMap = new HashMap<>();
        final Map<String, Attributes> shortNames = new HashMap<>();
        final Attributes[] features;
        final Map<Attributes, Integer> featureIndex = new HashMap<>();
        final BitSet[] dependencyMatrix;

        Liberty() {
            this.root = Paths.get(".");
            this.featureSubdir = this.root.resolve(Main.FEATURES_SUBDIR);
            // validate directories
            if (! Files.isDirectory(this.root)) throw new Error("Not a valid directory: " + this.root.toFile().getAbsolutePath());
            if (! Files.isDirectory(featureSubdir)) throw new Error("No feature subdirectory found: " + featureSubdir.toFile().getAbsolutePath());
            // parse feature manifests
            try (var paths = Files.list(featureSubdir)){
                paths
                        .filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".mf"))
                        .map(Main::read)
                        .forEach(f -> {
                            var oldValue = featureMap.put(Main.fullName(f), f);
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
            this.features = allFeatures().sorted(comparing(Main::fullName)).toArray(Attributes[]::new);
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
                    .sorted(comparing(Main::fullName))
                    .forEach(f -> Key.SUBSYSTEM_CONTENT.parseValues(f)
                            .filter(v -> "osgi.subsystem.feature".equals(v.getQualifier("type")))
                            .map(v -> v.id)
                            .filter(id -> !featureMap.containsKey(id))
                            .forEach(id -> System.err.printf("WARNING: feature '%s' depends on absent feature '%s'. " +
                                    "This dependency will be ignored.%n", fullName(f), id)));
        }

        Stream<Attributes> allFeatures() { return featureMap.values().stream(); }

        Stream<List<Attributes>> findFeaturePaths(List<List<QueryElement>> queries) {
            if (queries.isEmpty()) return Stream.empty();
            List<List<Attributes>> results = new ArrayList<>();
            for (List<QueryElement> query: queries)
                findFeaturePaths(allFeatures(), query, 0, new ArrayList<>(), results);
            return results.stream();
        }

        private void findFeaturePaths(Stream<Attributes> searchSpace, List<QueryElement> query, int index, List<Attributes> path, List<List<Attributes>> results) {
            if (query.size() == index) {
                results.add(path);
                    return;
            }
            QueryElement qe = query.get(index);
            int newIndex = index + 1;
            searchSpace
                    .filter(qe::matches)
                    .forEach(f -> findFeaturePaths(dependencies(f), query, newIndex, Lists.append(path, f), results));
        }

        Stream<Attributes> dependencies(Attributes rootFeature) {
            return dependencyMatrix[featureIndex.get(rootFeature)].stream().mapToObj(i -> features[i]);
        }
    }
}

