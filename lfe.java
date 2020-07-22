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
        DECORATE("Mark features with special qualifiers as follows:\n"
                + "\t\t\t[auto] auto-features are automatically enabled when certain combinations are in play\n"
                + "\t\t\t[singleton] - singleton features must only have one version installed in any server\n"
                + "\t\t\t[superseded] - features that have been superseded"
        ),
        FULL_NAMES("Always use the symbolic name of the feature, even if it has a short name"),
        SIMPLE_SORT("Sort using displayed name only"),
        WARN_MISSING("Warn if any features are referenced but not present"),
        TERMINATOR("Explicitly terminate the flags so that the following argument is interpreted as a query") {String toArg() { return "--"; }},
        NOT_A_FLAG(null),
        UNKNOWN(null);
        final String desc;
        Flag(String desc) { this.desc = desc; }
        String toArg() { return "--" + name().toLowerCase().replace('_', '-'); }
        static final Map<String, Flag> argMap =
                unmodifiableMap(publicFlags().collect(HashMap::new, (m, f) -> m.put(f.toArg(), f), Map::putAll));
        static Stream<Flag> publicFlags() { return Stream.of(Flag.values()).filter(f -> f.desc != null); }
        static Flag fromArg(String arg) { return arg.startsWith("--") ? argMap.getOrDefault(arg, UNKNOWN) : NOT_A_FLAG; }
    }

    final WLP wlp;
    final EnumSet<Flag> flags ;
    final Pattern query;
    final Comparator<Attributes> sortOrder;

    public static void main(String... args) throws Exception {
        try {
            new lfe(args).run();
        } catch (Error e) {
            System.err.println("ERROR: " + e.getMessage());
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
                    default: flags.add(flag); break;
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
        this.sortOrder = comparing(Visibility::from).thenComparing(this::featureName);
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
        for (Flag flag: flags) switch (flag) {
            case HELP:
                System.out.println("Usage: " + lfe.class.getSimpleName() + " [flag [flag ...] [--] <pattern> [pattern [pattern ...]]");
                System.out.println("Prints information about features when run from the root of an OpenLiberty installation. " +
                        "The patterns are treated as file glob patterns. " +
                        "Asterisks match any text, and question marks match a single character. " +
                        "If multiple patterns are specified, all features matching any pattern are listed.");
                System.out.println("Flags:");
                System.out.println(Flag.publicFlags()
                        .map(f -> String.format("\t%s\n\t\t%s", f.toArg(), f.desc))
                        .collect(joining("\n\n")));
                return;
            case WARN_MISSING:
                wlp.warnMissingFeatures();
                break;
            default:
                // other flags are used elsewhere
                break;
        }

        Visibility[] currentVisibility = {null};
        Consumer<Attributes> trackVisibility = f -> {
            Visibility newVis = Visibility.from(f);
            if (newVis == currentVisibility[0]) return;
            currentVisibility[0] = newVis;
            System.out.printf("[%s FEATURES]\n", newVis);

        };
        wlp.findFeatures(query)
                .sorted(comparing(Visibility::from).thenComparing(this::featureName))
                .peek(trackVisibility)
                .map(this::displayName)
                .forEach(System.out::println);
    }

    Attributes read(Path p) {
        try (InputStream in = new FileInputStream(p.toFile())) {
            return new Manifest(in).getMainAttributes();
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    String displayName(Attributes feature) {
        final String prefix = flags.contains(Flag.DECORATE)
                ? " "
                + Key.IBM_PROVISION_CAPABILITY.get(feature).map(s -> " auto").orElse("     ")
                + Key.SUBSYSTEM_SYMBOLICNAME.parseValues(feature).findFirst().map(v -> v.getQualifier("superseded")).filter(Boolean::valueOf).map(s -> " superseded").orElse("           ")
                + Key.SUBSYSTEM_SYMBOLICNAME.parseValues(feature).findFirst().map(v -> v.getQualifier("singleton")).filter(Boolean::valueOf).map(s -> " singleton").orElse("          ")
                + " "
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
                .peek(f -> System.out.printf("[%s]%n", displayName(f)))
                .flatMap(wlp::dependentFeatures)
                .map(lfe.this::displayName)
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
        Consumer<Attributes> recordFeatureName = f -> featureName[0] = displayName(f);
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
                    .forEach(f -> Key.SUBSYSTEM_CONTENT.parseValues(f)
                            .filter(v -> "osgi.subsystem.feature".equals(v.getQualifier("type")))
                            .map(v -> v.id)
                            .filter(id -> !!!featureMap.containsKey(id))
                            .forEach(id -> System.err.printf("WARNING: feature '%s' depends on absent feature '%s'. " +
                                            "This dependency will be ignored.%n",
                                    displayName(f), id)));
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


