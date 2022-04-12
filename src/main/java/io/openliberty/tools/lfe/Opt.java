package io.openliberty.tools.lfe;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.joining;

interface Opt<O extends Opt<O>> {
    String name();

    String desc();

    Stream<Opt<O>> implied();

    default String toArg() {
        return "--" + name().toLowerCase().replace('_', '-');
    }

    /**
     * Provides an indented (possibly multi-line) description suitable for printing on a line by itself
     */
    default String describe() {
        String separator = toArg().length() < 8 ? "\t" : String.format("%n\t\t");
        return String.format("\t%s%s%s", toArg(), separator, desc())
                + Optional.of(implied().map(Opt::toArg).collect(joining(" and ")))
                .filter(not(String::isEmpty))
                .map(s -> " Implies " + s + ".")
                .orElse("");
    }
}
