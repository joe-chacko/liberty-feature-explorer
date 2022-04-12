package io.openliberty.tools.lfe;

import java.io.FileInputStream;
import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

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

    final LibertyFeatures libertyTree;
    final EnumSet<Flag> flags;
    final List<List<QueryElement>> queries;
    final Comparator<List<Attributes>> pathOrdering;
    final Comparator<Attributes> featureOrdering;

    public Main(String... args) {
        var parser = new ArgParser(args);
        this.flags = parser.flags;
        this.queries = parser.query;
        this.libertyTree = new LibertyFeatures(flags.contains(Flag.IGNORE_DUPLICATES));
        this.featureOrdering = flags.contains(Flag.SIMPLE_SORT)
                ? comparing(this::featureName)
                : comparing(Visibility::from).thenComparing(this::featureName);
        this.pathOrdering = Lists.comparingEachElement(featureOrdering);
    }

    void run() {
        // some flags need processing up front
        if (flags.contains(Flag.HELP)) { printUsage(); return; }
        if (flags.contains(Flag.WARN_MISSING)) libertyTree.warnMissingFeatures();

        printHeadersIfNeeded();

        final Consumer<Attributes> printVisibilityHeadings = usingHeadings() ? printVisibilityHeadings() : (f -> {});
        final String initialIndent = usingHeadings() ? "  " : "";

        if (flags.contains(Flag.PATHS)) {
            libertyTree.findFeaturePaths(queries)
                    .sorted(pathOrdering)
                    .distinct()
                    .forEach(path -> {
                        String indent = path.stream()
                                .map(this::featureName)
                                .collect(joining("/"))
                                .replaceFirst("[^/]*$", "");
                        System.out.println(formatFeature(indent, path.get(path.size() - 1)));
                    });
        } else if (flags.contains(Flag.TREE)) {
            libertyTree.findFeaturePaths(queries)
                    .sorted(pathOrdering)
                    .distinct()
                    // collect these into a tree structure
                    .collect(TreeNode<Attributes>::new, TreeNode::addPath, TreeNode::combine)
                    // print the tree in ASCII
                    .traverseDepthFirst(initialIndent, printVisibilityHeadings,
                            prefix -> feature -> System.out.println(formatFeature(prefix, feature)));
        } else {
            libertyTree.findFeaturePaths(queries)
                    .map(Lists::last)
                    .sorted(featureOrdering)
                    .distinct()
                    .peek(printVisibilityHeadings)
                    .forEach(f -> System.out.println(formatFeature(initialIndent, f)));

        }
    }

    private void printHeadersIfNeeded() {
        if (flags.contains(Flag.DECORATE) && ! flags.contains(Flag.TABS)) {
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
        final boolean useTabs = flags.contains(Flag.TABS);
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

}
