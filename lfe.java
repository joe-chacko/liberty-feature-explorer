//usr/bin/env java --source 11 $0 "$@"; exit $?
// The above line must come first in this file to allow it to be executed as a script on unix systems.
// For other systems, invoke this file using this command:
//  java --source 11 <name of this file>

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
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class lfe {
    static class MisuseError extends Error {
        MisuseError(String message) { super(message); }
    }

    enum Flag {WARN_MISSING_FEATURES}
    private final WLP wlp;
    private final EnumSet<Flag> flags ;
    private final Pattern query;

    public static void main(String... args) throws Exception {
        try {
            new lfe(args).run();
        } catch (MisuseError e) {
            System.err.println("ERROR: " + e.getMessage());
            System.err.println();
            System.err.println("Usage: " + lfe.class.getSimpleName() + " [--warn-missing-features] pattern");
            System.err.println("Prints information about features when run from the root of an OpenLiberty installation.");
        }
    }

    public lfe(String... args) {
        class ArgParser {
            final EnumSet<Flag> flags = EnumSet.noneOf(Flag.class);
            final Pattern query;
            int argIndex = 0;

            ArgParser() {
                parseOptions();
                this.query = parseRemainingArguments();
            }

            private void parseOptions() {
                for (; argIndex < args.length; argIndex++) {
                    switch (args[argIndex]) {
                        case "--warn-missing-features":
                            flags.add(Flag.WARN_MISSING_FEATURES);
                            break;
                        case "--":
                            ++argIndex;
                            return;
                        default:
                            if (args[argIndex].startsWith("--")) throw new MisuseError("unknown flag or option '" + args[argIndex] + "'");
                            return; // without incrementing i
                    }
                }
            }

            private Pattern parseRemainingArguments() {
                return Pattern.compile(
                        IntStream.range(argIndex, args.length)
                        .peek(i -> argIndex = i)
                        .mapToObj(i -> args[i])
                        .map(lfe::globToRegex)
                        .collect(Collectors.joining("|", "(?i:", ")")));
            }
        }
        var parser = new ArgParser();
        this.flags = parser.flags;
        this.query = parser.query;
        this.wlp = new WLP();
        if (flags.contains(Flag.WARN_MISSING_FEATURES)) wlp.warnMissingFeatures();
    }

    private static String globToRegex(String glob) {
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


    private void run() {
//        printTolerantFeatures(wlp);
//        System.out.println();
        // list all the features with "cdi" in the name
//        printMatchingFeatures(wlp, "cdi");
//        System.out.println();
        // print all bundles pulled in by auto-features
//        printBundlesIncludedInAutoFeatures(wlp);
//        System.out.println();
        wlp.findFeatures(query)
                .peek(f -> System.out.printf("===%s===%n", Features.name(f)))
                .flatMap(wlp::dependentFeatures)
                .map(Features::name)
                .forEach(n -> System.out.printf("\t%s%n", n));

        printMatchingFeatures(wlp, query);
    }

    private static void printBundlesIncludedInAutoFeatures(WLP wlp) {
        wlp.features()
                .filter(Key.IBM_PROVISION_CAPABILITY)
                .filter(Key.SUBSYSTEM_CONTENT)
                .flatMap(Key.SUBSYSTEM_CONTENT::parseValues)
                .filter(v -> !!! v.metadata.containsKey("type")) // find only the bundles
                .peek(System.out::println)
                .map(v -> v.id)
                .collect(TreeSet::new, Set::add, Set::addAll) // use a TreeSet so it is sorted
                .forEach(System.out::println);
    }

    private static void printMatchingFeatures(WLP wlp, Pattern p) {
        wlp.features()
                .map(Key.SUBSYSTEM_SYMBOLICNAME)
                .filter(f -> p.matcher(f).matches())
                .forEach(System.out::println);
    }

    private static void printTolerantFeatures(WLP wlp) {
        // print all features that tolerate other features
        String[] featureName = {null};
        Consumer<Attributes> recordFeatureName = f -> featureName[0] = Features.name(f);
        Consumer<ValueElement> reportFeatureName = s -> {
            if (featureName[0] == null) return;
            System.out.printf("===%s===%n", featureName[0]);
            featureName[0] = null;
        };
        wlp.features()
                .peek(recordFeatureName)
                .filter(Key.SUBSYSTEM_CONTENT)
                .flatMap(Key.SUBSYSTEM_CONTENT::parseValues)
                .filter(v -> v.metadata.containsKey("type"))
                .filter(v -> v.metadata.containsKey("ibm.tolerates"))
                .filter(v -> "osgi.subsystem.feature".equals(v.metadata.get("type")))
                .peek(reportFeatureName)
                .forEach(v -> {
                    System.out.printf("\t%s (%s)%n", v.id, v.metadata.get("ibm.tolerates"));
                });
    }
}

class ValueElement {
    static final Pattern ATOM_PATTERN = Pattern.compile("(([^\";\\\\]|\\\\.)+|\"([^\\\\\"]|\\\\.)*\")+");
    final String id;
    final Map<? extends String, ? extends String> metadata;
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
        this.metadata = Collections.unmodifiableMap(map);
    }

    @Override
    public String toString() {
        return String.format("%88s : %s", id, metadata);
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
    DEFAULT, PRIVATE, PROTECTED, PUBLIC;
    private static Pattern VISIBILITY_PATTERN = Pattern.compile(";visibility:=([^ ;]+)");

    static Visibility valueOf(Attributes feature) {
        return Key.SUBSYSTEM_SYMBOLICNAME.get(feature)
                .map(s -> s.replaceAll(" ", ""))
                .map(VISIBILITY_PATTERN::matcher)
                .map(Matcher::results)
                .orElse(Stream.empty())
                .findFirst()
                .map(m -> m.group(1))
                .map(String::toUpperCase)
                .map(Visibility::valueOf)
                .orElse(Visibility.DEFAULT);
    }

    public boolean test(Attributes feature) {
        return this == valueOf(feature);
    }
}

enum Features {
    ;


    static Attributes read(Path p) {
        try (InputStream in = new FileInputStream(p.toFile())) {
            return new Manifest(in).getMainAttributes();
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    static String name(Attributes feature) { return Key.IBM_SHORTNAME.get(feature).orElse(fullName(feature)); }

    static String fullName(Attributes feature) {
        return Key.SUBSYSTEM_SYMBOLICNAME
                .parseValues(feature)
                .findFirst()
                .get()
                .id;
    }
}

class WLP {
    static final Path FEATURES_SUBDIR = Paths.get("lib/features");
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
                    .map(Features::read)
                    .forEach(f -> {
                        if (null != featureMap.put(Features.fullName(f), f))
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
                    String rootID = Features.fullName(f);
                    Key.SUBSYSTEM_CONTENT.parseValues(f)
                            .filter(v -> "osgi.subsystem.feature".equals(v.metadata.get("type")))
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
                        .filter(v -> "osgi.subsystem.feature".equals(v.metadata.get("type")))
                        .map(v -> v.id)
                        .filter(id -> !!!featureMap.containsKey(id))
                        .forEach(id -> System.err.printf("WARNING: feature '%s' depends on absent feature '%s'. " +
                                "This dependency will be ignored.%n",
                                Features.name(f), id)));
    }

    Stream<Attributes> features() { return featureMap.values().stream(); }

    public Stream<Attributes> findFeatures(Pattern featurePattern) {
        return featureMap.values()
                .stream()
                .filter(f -> Key.IBM_SHORTNAME.get(f)
                            .map(featurePattern::matcher)
                            .map(Matcher::matches)
                            .filter(b -> b) // only consider actual matches
                            .orElse(Key.SUBSYSTEM_SYMBOLICNAME.parseValues(f)
                                    .findFirst()
                                    .map(v -> v.id)
                                    .map(featurePattern::matcher)
                                    .map(Matcher::matches)
                                    .orElse(false)));
    }

    public Stream<Attributes> dependentFeatures(Attributes rootFeature) {
        return Key.SUBSYSTEM_CONTENT.parseValues(rootFeature)
                .filter(v -> "osgi.subsystem.feature".equals(v.metadata.get("type")))
                .map(v -> v.id)
                .map(featureMap::get)
                .filter(Objects::nonNull);
    }
}


