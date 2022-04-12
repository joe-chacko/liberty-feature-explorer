package io.openliberty.tools.lfe;

import java.util.EnumSet;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toUnmodifiableList;

final class ArgParser {
    final EnumSet<Flag> flags = EnumSet.noneOf(Flag.class);
    final List<List<QueryElement>> query;
    final String[] args;
    int argIndex;

    ArgParser(String... args) {
        this.args = args;
        this.argIndex = 0;
        parseOptions();
        this.query = parseReLFEingArguments();
    }

    void parseOptions() {
        for (; argIndex < args.length; argIndex++) {
            final Flag flag = Flag.fromArg(args[argIndex]);
            switch (flag) {
                case TERMINATOR:
                    ++argIndex;
                    return;
                case NOT_A_FLAG:
                    return;
                case UNKNOWN:
                    throw new MisuseError("unknown flag or option '" + args[argIndex] + "'");
                default:
                    flag.addTo(flags);
                    break;
            }
        }
    }

    List<List<QueryElement>> parseReLFEingArguments() {
        return IntStream.range(argIndex, args.length)
                .peek(i -> argIndex = i)
                .mapToObj(i -> args[i])
                .map(ArgParser::parseQuery)
                .collect(toUnmodifiableList());
    }

    private static List<QueryElement> parseQuery(String s) {
        return Stream.of(s.split("/"))
                .map(QueryElement::of)
                .collect(toUnmodifiableList());
    }
}
