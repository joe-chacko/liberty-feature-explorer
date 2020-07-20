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
import java.util.stream.Stream;

public class lfe {
    public static void main(String... args) throws Exception {
        final WLP wlp = new WLP();
//        printTolerantFeatures(wlp);
//        System.out.println();
        // list all the features with "cdi" in the name
//        printMatchingFeatures(wlp, "cdi");
//        System.out.println();
        // print all bundles pulled in by auto-features
//        printBundlesIncludedInAutoFeatures(wlp);
//        System.out.println();
        String featurePattern = args[0].toLowerCase();
        wlp.findFeatures(featurePattern)
                .peek(f -> System.out.printf("===%s===%n", Features.name(f)))
                .flatMap(wlp::dependentFeatures)
                .map(Features::name)
                .forEach(n -> System.out.printf("\t%s%n", n));

//        printMatchingFeatures(wlp, featurePattern);
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

    private static void printMatchingFeatures(WLP wlp, String substring) {
        wlp.features()
                .map(Key.SUBSYSTEM_SYMBOLICNAME)
                .filter(s -> s.contains(substring))
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
    String get(Attributes feature) { return feature.getValue(name); }
    Stream<ValueElement> parseValues(Attributes feature) {
        return ELEMENT_PATTERN.matcher(this.get(feature))
                .results()
                .map(MatchResult::group)
                .map(ValueElement::new);
    }
    public String apply(Attributes feature) { return get(feature); }
    public boolean test(Attributes feature) { return isPresent(feature); }
}

enum Visibility implements Predicate<Attributes> {
    DEFAULT, PRIVATE, PROTECTED, PUBLIC;
    private static Pattern VISIBILITY_PATTERN = Pattern.compile(";visibility:=([^ ;]+)");

    static Visibility valueOf(Attributes feature) {
        Matcher m = VISIBILITY_PATTERN.matcher(Key.SUBSYSTEM_SYMBOLICNAME.get(feature).replaceAll(" ", ""));
        if (m.find()) {
            String s = m.group(1);
            return Visibility.valueOf(s.toUpperCase());
        }
        return DEFAULT;
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

    static String name(Attributes feature) {
        return Key.IBM_SHORTNAME.isPresent(feature) ? Key.IBM_SHORTNAME.get(feature) : fullName(feature).toString();
    }

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
                        if (Key.IBM_SHORTNAME.isPresent(f)) {
                            if (null != shortNames.put(Key.IBM_SHORTNAME.get(f), f))
                                System.err.println("WARNING: duplicate short name found: " + Key.IBM_SHORTNAME.get(f));;
                        }
                    });
        } catch (IOException e) {
            throw new IOError(e);
        }
        // process features to check dependencies and list bundles
        features()
                .filter(Key.SUBSYSTEM_CONTENT)
                .forEach(f -> Key.SUBSYSTEM_CONTENT.parseValues(f)
                        .filter(v -> "osgi.subsystem.feature".equals(v.metadata.get("type")))
                        .map(v -> v.id)
                        .filter(id -> !!!featureMap.containsKey(id))
                        .forEach(id -> System.err.printf("WARNING: feature '%s' depends on absent feature '%s'. " +
                                "This dependency will be ignored.%n",
                                Features.name(f), id)));
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

    Stream<Attributes> features() { return featureMap.values().stream(); }

    public Stream<Attributes> findFeatures(String featurePattern) {
        return featureMap.values()
                .stream()
                .filter(f -> {
                    final String shortName = Key.IBM_SHORTNAME.get(f);
                    if (shortName != null && shortName.toLowerCase().contains(featurePattern.toLowerCase())) return true;
                    return Key.SUBSYSTEM_SYMBOLICNAME.parseValues(f)
                            .findFirst()
                            .map(v -> v.id.toLowerCase().contains(featurePattern.toLowerCase()))
                            .orElse(false);
                });
    }

    public Stream<Attributes> dependentFeatures(Attributes rootFeature) {
        return Key.SUBSYSTEM_CONTENT.parseValues(rootFeature)
                .filter(v -> "osgi.subsystem.feature".equals(v.metadata.get("type")))
                .map(v -> v.id)
                .map(featureMap::get);
    }
}


