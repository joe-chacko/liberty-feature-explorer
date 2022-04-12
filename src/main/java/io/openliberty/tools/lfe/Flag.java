package io.openliberty.tools.lfe;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static java.util.Arrays.stream;
import static java.util.Collections.unmodifiableMap;
import static java.util.stream.Collectors.joining;

enum Flag implements Opt<Flag> {
    HELP("Print this usage message."),
    DECORATE("Mark features with special qualifiers as follows:"
            + "%n\t\t\tpublic|private|protected - the declared visibility"
            + "%n\t\t\tauto - feature automatically enabled in certain conditions"
            + "%n\t\t\tsuperseded - has been superseded by another feature"
            + "%n\t\t\tsingleton - only one version of this feature can be installed per server"),
    FULL_NAMES("Always use the symbolic name of the feature, even if it has a short name."),
    TREE("Display all matching dependency trees"),
    PATHS("Display all matching paths (supersedes " + TREE.toArg() + ")"),
    TABS("Suppress headers and use tabs to delimit fields to aid scripting.", DECORATE),
    SIMPLE_SORT("Sort by full name. Do not categorise by visibility.", FULL_NAMES),
    WARN_MISSING("Warn if any features are referenced but not present."),
    IGNORE_DUPLICATES("Do NOT report duplicate feature attributes (e..g short names)."),
    TERMINATOR("Explicitly terminate the flags so that the following argument is interpreted as a query.") {
        public String toArg() {return "--";}
    },
    NOT_A_FLAG(null),
    UNKNOWN(null);
    final String desc;
    final Flag[] impliedFlags;

    Flag(String desc, Flag... impliedFlags) {
        this.desc = Optional.ofNullable(desc).map(String::format).orElse(null);
        this.impliedFlags = impliedFlags;
    }

    public String desc() {
        return desc;
    }

    public Stream<Opt<Flag>> implied() {
        return stream(impliedFlags);
    }

    void addTo(Collection<? super Flag> flags) {
        flags.add(this);
        for (Flag implied: impliedFlags) flags.add(implied);
    }

    /**
     * Provides a multi-line description of all (public) flags
     */
    static String describeAll() {
        return String.format(publicFlags().map(Flag::describe).collect(joining("%n%n", "Flags:%n", "")));
    }

    static final Map<String, Flag> argMap = unmodifiableMap(publicFlags().collect(HashMap::new, (m, f) -> m.put(f.toArg(), f), Map::putAll));

    static Stream<Flag> publicFlags() {
        return Stream.of(Flag.values()).filter(f -> f.desc != null);
    }

    static Flag fromArg(String arg) {
        return arg.startsWith("--") ? argMap.getOrDefault(arg, UNKNOWN) : NOT_A_FLAG;
    }
}
