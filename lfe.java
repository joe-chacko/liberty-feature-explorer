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
    import java.util.*;
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

    import static java.util.Collections.unmodifiableMap;
    import static java.util.Comparator.comparing;
    import static java.util.stream.Collectors.joining;

    public class lfe {
        static final Path FEATURES_SUBDIR = Paths.get("lib/features");

        static class MisuseError extends Error { MisuseError(String message) { super(message); }}

        enum Flag {
            HELP("Print this usage message."),
            DECORATE("Mark features with special qualifiers as follows:"
                    + "%n\t\t\tpublic|private|protected - the declared visibility"
                    + "%n\t\t\tauto - feature automatically enabled in certain conditions"
                    + "%n\t\t\tsuperseded - has been superseded by another feature"
                    + "%n\t\t\tsingleton - only one version of this feature can be installed per server"
            ),
            FULL_NAMES("Always use the symbolic name of the feature, even if it has a short name."),
            TAB_DELIMITERS("Suppress headers and use tabs to delimit fields to aid scripting. Implies --decorate.") { void addTo(Set<Flag> flags) { super.addTo(flags); DECORATE.addTo(flags); }},
            SIMPLE_SORT("Sort by full name. Do not categorise by visibility. Implies --full-names.") { void addTo(Set<Flag> flags) { super.addTo(flags); FULL_NAMES.addTo(flags); }},
            WARN_MISSING("Warn if any features are referenced but not present."),
            TERMINATOR("Explicitly terminate the flags so that the following argument is interpreted as a query.") {String toArg() { return "--"; }},
            NOT_A_FLAG(null),
            UNKNOWN(null);
            final String desc;
            Flag(String desc) { this.desc = Optional.ofNullable(desc).map(String::format).orElse(null); }

            /** Provides a multi-line description of all (public) flags */
            static String describeAll() {
                return String.format(publicFlags().map(Flag::describe).collect(joining("%n%n", "Flags:%n", "")));
            }

            /** Provides an indented (possibly multi-line) description suitable for printing on a line by itself */
            private String describe() {
                String arg = toArg();
                String separator = arg.length() < 8 ? "\t" : String.format("%n\t\t");
                return String.format("\t%s%s%s", arg, separator, this.desc);
            }

            void addTo(Set<Flag> flags) { flags.add(this); }
            String toArg() { return "--" + name().toLowerCase().replace('_', '-'); }
            static final Map<String, Flag> argMap =
                    unmodifiableMap(publicFlags().collect(HashMap::new, (m, f) -> m.put(f.toArg(), f), Map::putAll));
            static Stream<Flag> publicFlags() { return Stream.of(Flag.values()).filter(f -> f.desc != null); }
            static Flag fromArg(String arg) { return arg.startsWith("--") ? argMap.getOrDefault(arg, UNKNOWN) : NOT_A_FLAG; }
        }

        final WLP wlp;
        final EnumSet<Flag> flags ;
        final Pattern query;

        public static void main(String... args) throws Exception {
            try {
                new lfe(args).run();
            } catch (MisuseError e) {
                System.err.println("ERROR: " + e.getMessage());
            } catch (Throwable e) {
                System.err.println("ERROR: " + e.getMessage());
                e.printStackTrace();
            }
        }

        static class ArgParser {
            final EnumSet<Flag> flags = EnumSet.noneOf(Flag.class);
            final Pattern query;
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
                        default: flag.addTo(flags); break;
                        case TERMINATOR: ++argIndex;
                        case NOT_A_FLAG: return;
                        case UNKNOWN: throw new MisuseError("unknown flag or option '" + args[argIndex] + "'");
                    }
                }
            }
            Pattern parseRemainingArguments() {
                return Pattern.compile(
                        IntStream.range(argIndex, args.length)
                                .peek(i -> argIndex = i)
                                .mapToObj(i -> args[i])
                                .map(lfe::globToRegex)
                                .collect(joining("|", "(?i:", ")")));
            }

        }

        public lfe(String... args) {
            var parser = new ArgParser(args);
            this.flags = parser.flags;
            this.query = parser.query;
            this.wlp = new WLP();
        }

        static String globToRegex(String glob) {
            var scanner = new Scanner(glob);
            var sb = new StringBuilder();
            scanner.useDelimiter("((?<=[?*])|(?=[?*]))"); // delimit immediately before and/or after a * or ?
            while (scanner.hasNext()) {
                String token = scanner.next();
                switch (token) {
                    case "?": sb.append("."); break;
                    case "*": sb.append(".*"); break;
                    default: sb.append(Pattern.quote(token));
                }
            }
            return sb.toString();
        }

        void run() {
            // some flags need processing up front
            for (Flag flag: flags) switch (flag) {
                case HELP: printUsage(); return;
                case WARN_MISSING: wlp.warnMissingFeatures();break;
            }

            if (flags.contains(Flag.DECORATE) && !!! flags.contains(Flag.TAB_DELIMITERS)) {
                // print some heading columns first
                System.out.println("# VISIBILITY AUTO SUPERSEDED SINGLETON FEATURE NAME");
                System.out.println("# ========== ==== ========== ========= ============");
            }
            wlp.findFeatures(query)
                    .sorted(sortOrder())
                    .peek(printVisibilityHeadings())
                    .map(this::displayFeature)
                    .forEach(System.out::println);
        }

        private Comparator<Attributes> sortOrder() {
            return flags.contains(Flag.SIMPLE_SORT)
                    ? comparing(this::featureName)
                    : comparing(Visibility::from).thenComparing(this::featureName);
        }

        private Consumer<Attributes> printVisibilityHeadings() {
            // Disable headings if using simple-sort or printing feature qualifiers
            if (flags.contains(Flag.SIMPLE_SORT) || flags.contains(Flag.DECORATE)) return feature -> {};
            // Use a 'holder' to track the previous visibility
            Visibility[] currentVisibility = {null};
            return feature -> {
                Visibility newVis = Visibility.from(feature);
                if (newVis != currentVisibility[0]) {
                    // the visibility has changed, so print out a heading
                    System.out.printf("[%s FEATURES]%n", newVis);
                    currentVisibility[0] = newVis;
                }
                System.out.print("  ");
            };
        }

        private static void printUsage() {
            System.out.println("Usage: " + lfe.class.getSimpleName() + " [flag [flag ...] [--] <pattern> [pattern [pattern ...]]");
            System.out.println("Prints information about features when run from a Liberty root directory." );
            System.out.println("The patterns are treated as file glob patterns.");
            System.out.println("Asterisks match any text, and question marks match a single character.");
            System.out.println("If multiple patterns are given, features matching any pattern are listed.");
            System.out.println(Flag.describeAll());
        }

        Attributes read(Path p) {
            try (InputStream in = new FileInputStream(p.toFile())) {
                return new Manifest(in).getMainAttributes();
            } catch (IOException e) {
                throw new IOError(e);
            }
        }

        String displayFeature(Attributes feature) {
            final boolean useTabs = flags.contains(Flag.TAB_DELIMITERS);
            final char DELIM = useTabs ? '\t' : ' ';
            final String visibility = Visibility.from(feature).format();
            final String prefix = flags.contains(Flag.DECORATE)
                    ? (useTabs ? "" : "  ") // indent unless using tabs
                    + (useTabs ? visibility.trim() : visibility)
                    + DELIM + Key.IBM_PROVISION_CAPABILITY.get(feature).map(s -> "auto").orElse("    ")
                    + DELIM + Key.SUBSYSTEM_SYMBOLICNAME.parseValues(feature).findFirst().map(v -> v.getQualifier("superseded")).filter(Boolean::valueOf).map(s -> "superseded").orElse("          ")
                    + DELIM + Key.SUBSYSTEM_SYMBOLICNAME.parseValues(feature).findFirst().map(v -> v.getQualifier("singleton")).filter(Boolean::valueOf).map(s -> "singleton").orElse("         ")
                    + DELIM
                    : "";
            return prefix + featureName(feature);
        }

        String featureName(Attributes feature) { return flags.contains(Flag.FULL_NAMES) ? fullName(feature) : shortName(feature); }

        String fullName(Attributes feature) {
            return Key.SUBSYSTEM_SYMBOLICNAME
                    .parseValues(feature)
                    .findFirst()
                    .get()
                    .id;
        }

        String shortName(Attributes feature) { return Key.IBM_SHORTNAME.get(feature).orElseGet(() -> fullName(feature)); }

        private void printDeps() {
            wlp.findFeatures(query)
                    .peek(f -> System.out.printf("[%s]%n", displayFeature(f)))
                    .flatMap(wlp::dependentFeatures)
                    .map(lfe.this::displayFeature)
                    .forEach(n -> System.out.printf("\t%s%n", n));
        }

        void printBundlesIncludedInAutoFeatures(WLP wlp) {
            wlp.features()
                    .filter(Key.IBM_PROVISION_CAPABILITY)
                    .filter(Key.SUBSYSTEM_CONTENT)
                    .flatMap(Key.SUBSYSTEM_CONTENT::parseValues)
                    .filter(v -> !!! v.hasQualifier("type")) // find only the bundles
                    .peek(System.out::println)
                    .map(v -> v.id)
                    .collect(TreeSet::new, Set::add, Set::addAll) // use a TreeSet so it is sorted
                    .forEach(System.out::println);
        }

        void printTolerantFeatures(WLP wlp) {
            // print all features that tolerate other features
            String[] featureName = {null};
            Consumer<Attributes> recordFeatureName = f -> featureName[0] = displayFeature(f);
            Consumer<ValueElement> reportFeatureName = s -> {
                if (featureName[0] == null) return;
                System.out.printf("===%s===%n", featureName[0]);
                featureName[0] = null;
            };
            wlp.features()
                    .peek(recordFeatureName)
                    .filter(Key.SUBSYSTEM_CONTENT)
                    .flatMap(Key.SUBSYSTEM_CONTENT::parseValues)
                    .filter(v -> v.hasQualifier("type"))
                    .filter(v -> v.hasQualifier("ibm.tolerates"))
                    .filter(v -> "osgi.subsystem.feature".equals(v.getQualifier("type")))
                    .peek(reportFeatureName)
                    .forEach(v -> {
                        System.out.printf("\t%s (%s)%n", v.id, v.getQualifier("ibm.tolerates"));
                    });
        }

        static class ValueElement {
            static final Pattern ATOM_PATTERN = Pattern.compile("(([^\";\\\\]|\\\\.)+|\"([^\\\\\"]|\\\\.)*\")+");
            final String id;
            private final Map<? extends String, String> qualifiers;

            ValueElement(String text) {
                Matcher m = ATOM_PATTERN.matcher(text);
                m.find();
                this.id = m.group();
                Map<String, String> map = new TreeMap<>();
                while(m.find(m.end())) {
                    String[] parts = m.group().split(":?=", 2);
                    if (null != map.put(parts[0].trim(), parts[1].trim().replaceFirst("^\"(.*)\"$", "$1")))
                        System.err.printf("WARNING: duplicate metadata key '%s' detected in string '%s'", parts[0], text);
                }
                this.qualifiers = unmodifiableMap(map);
            }

            boolean hasQualifier(String key) { return qualifiers.containsKey(key); }

            String getQualifier(String key) { return qualifiers.get(key); }

            public String toString() {
                return String.format("%88s : %s", id, qualifiers);
            }
        }

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
            static final Pattern ELEMENT_PATTERN = Pattern.compile("(([^\",\\\\]|\\\\.)+|\"([^\\\\\"]|\\\\.)*\")+");
            final Attributes.Name name;
            Key(String name) { this.name = new Attributes.Name(name); }
            boolean isPresent(Attributes feature) {
                return feature.containsKey(name);
            }
            boolean isAbsent(Attributes feature) { return !!! isPresent(feature); }
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
            DEFAULT;

            static Visibility from(Attributes feature) {
                return Key.SUBSYSTEM_SYMBOLICNAME.parseValues(feature)
                        .findFirst()
                        .map(v -> v.getQualifier("visibility"))
                        .map(String::toUpperCase)
                        .map(Visibility::valueOf)
                        .orElse(Visibility.DEFAULT);
            }

            String format() { return String.format("%-10s", name().toLowerCase()); }

            public boolean test(Attributes feature) { return this == from(feature); }
        }

        class WLP {
            final Path root;
            final Path featureSubdir;
            final Map<String, Attributes> featureMap = new TreeMap<>();
            final Map<String, Attributes> shortNames = new TreeMap<>();

            WLP() { this("."); }

            WLP(String root) {
                this.root = Paths.get(root);
                this.featureSubdir = this.root.resolve(FEATURES_SUBDIR);
                // validate directories
                if (!!! Files.isDirectory(this.root)) throw new Error("Not a valid directory: " + this.root.toFile().getAbsolutePath());
                if (!!! Files.isDirectory(featureSubdir)) throw new Error("No feature subdirectory found: " + featureSubdir.toFile().getAbsolutePath());
                // parse feature manifests
                try {
                    Files.list(featureSubdir)
                            .filter(Files::isRegularFile)
                            .filter(p -> p.toString().endsWith(".mf"))
                            .map(lfe.this::read)
                            .forEach(f -> {
                                if (null != featureMap.put(fullName(f), f))
                                    System.err.println("WARNING: duplicate symbolic name found: " + Key.SUBSYSTEM_SYMBOLICNAME.get(f));
                                Key.IBM_SHORTNAME.get(f).ifPresent(shortName -> {
                                    if (null != shortNames.put(shortName, f))
                                        System.err.println("WARNING: duplicate short name found: " + shortName);
                                });
                            });
                } catch (IOException e) {
                    throw new IOError(e);
                }
                // follow dependencies
                features()
                        .filter(Key.SUBSYSTEM_CONTENT)
                        .forEach(f -> {
                            String rootID = fullName(f);
                            Key.SUBSYSTEM_CONTENT.parseValues(f)
                                    .filter(v -> "osgi.subsystem.feature".equals(v.getQualifier("type")))
                                    .forEach(v -> {
                                        String depID = v.id;
                                        // check for non matching version and try tolerated versions instead


                                    });
                        });
            }

            void warnMissingFeatures() {
                features()
                        .filter(Key.SUBSYSTEM_CONTENT)
                        .sorted(comparing(lfe.this::fullName))
                        .forEach(f -> Key.SUBSYSTEM_CONTENT.parseValues(f)
                                .filter(v -> "osgi.subsystem.feature".equals(v.getQualifier("type")))
                                .map(v -> v.id)
                                .filter(id -> !!!featureMap.containsKey(id))
                                .forEach(id -> System.err.printf("WARNING: feature '%s' depends on absent feature '%s'. " +
                                                "This dependency will be ignored.%n",
                                        fullName(f), id)));
            }

            Stream<Attributes> features() { return featureMap.values().stream(); }

            public Stream<Attributes> findFeatures(Pattern featurePattern) {
                return featureMap.values()
                        .stream()
                        .filter(f -> Key.IBM_SHORTNAME.get(f)
                                .map(featurePattern::matcher)
                                .map(Matcher::matches)
                                .filter(Boolean::booleanValue) // discard non-matches
                                .orElse(Key.SUBSYSTEM_SYMBOLICNAME.parseValues(f)
                                        .findFirst()
                                        .map(v -> v.id)
                                        .map(featurePattern::matcher)
                                        .map(Matcher::matches)
                                        .orElse(false)));
            }

            public Stream<Attributes> dependentFeatures(Attributes rootFeature) {
                return Key.SUBSYSTEM_CONTENT.parseValues(rootFeature)
                        .filter(v -> "osgi.subsystem.feature".equals(v.getQualifier("type")))
                        .map(v -> v.id)
                        .map(featureMap::get)
                        .filter(Objects::nonNull);
            }
        }
    }


