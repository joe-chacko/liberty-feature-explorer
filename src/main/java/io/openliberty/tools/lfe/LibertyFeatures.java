package io.openliberty.tools.lfe;

import java.io.IOError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.jar.Attributes;
import java.util.stream.Stream;

import static java.util.Comparator.comparing;

final class LibertyFeatures {
    final Path root;
    final Path featureSubdir;
    final Map<String, Attributes> featureMap = new HashMap<>();
    final Map<String, Attributes> shortNames = new HashMap<>();
    final Attributes[] features;
    final Map<Attributes, Integer> featureIndex = new HashMap<>();
    final BitSet[] dependencyMatrix;

    LibertyFeatures(boolean ignoreDuplicates) {
        this.root = Paths.get(".");
        this.featureSubdir = this.root.resolve(Main.FEATURES_SUBDIR);
        // validate directories
        if (!Files.isDirectory(this.root))
            throw new Error("Not a valid directory: " + this.root.toFile().getAbsolutePath());
        if (!Files.isDirectory(featureSubdir))
            throw new Error("No feature subdirectory found: " + featureSubdir.toFile().getAbsolutePath());
        // parse feature manifests
        try (var paths = Files.list(featureSubdir)) {
            paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".mf"))
                    .map(Main::read)
                    .forEach(f -> {
                        var oldValue = featureMap.put(Main.fullName(f), f);
                        if (null != oldValue && !ignoreDuplicates)
                            System.err.println("WARNING: duplicate symbolic name found: " + Key.SUBSYSTEM_SYMBOLICNAME.get(f));
                        Key.IBM_SHORTNAME.get(f)
                                .map(shortName -> shortNames.put(shortName, f))
                                .filter(whatever -> !ignoreDuplicates)
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
                                "This dependency will be ignored.%n", Main.fullName(f), id)));
    }

    Stream<Attributes> allFeatures() {
        return featureMap.values().stream();
    }

    Stream<List<Attributes>> findFeaturePaths(List<List<QueryElement>> queries) {
        if (queries.isEmpty()) return Stream.empty();
        List<List<Attributes>> results = new ArrayList<>();
        for (List<QueryElement> query : queries)
            findFeaturePaths(allFeatures(), query, 0, new ArrayList<>(), results);
        return results.stream();
    }

    private void findFeaturePaths(Stream<Attributes> searchSpace, List<QueryElement> query, int index, List<Attributes> path, List<List<Attributes>> results) {
        if (query.size() == index) {
            results.add(path);
            index--;
            while (!query.get(index).isStretchy()) if (index == 0) return;
            else index--;
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
