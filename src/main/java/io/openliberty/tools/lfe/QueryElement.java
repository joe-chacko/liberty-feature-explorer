package io.openliberty.tools.lfe;

import java.util.Scanner;
import java.util.jar.Attributes;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.openliberty.tools.lfe.QueryElement.SpecialQueryElement.MATCH_MANY_FEATURES;
import static io.openliberty.tools.lfe.QueryElement.SpecialQueryElement.MATCH_ONE_FEATURE;

interface QueryElement {
    default boolean matches(Attributes feature) {
        return true;
    }

    boolean isStretchy();

    enum SpecialQueryElement implements QueryElement {
        MATCH_ONE_FEATURE {
            public boolean isStretchy() {
                return false;
            }
        },
        MATCH_MANY_FEATURES {
            public boolean isStretchy() {
                return true;
            }
        }
    }

    static QueryElement of(String glob) {
        switch (glob) {
            case "*":
                return MATCH_ONE_FEATURE;
            case "**":
                return MATCH_MANY_FEATURES;
            default:
                return new QueryElement() {
                    final Pattern pattern = Pattern.compile(globToRegex(glob));

                    public boolean matches(Attributes feature) {
                        return Key.IBM_SHORTNAME.get(feature)
                                .map(pattern::matcher)
                                .map(Matcher::matches)
                                .filter(Boolean::booleanValue) // discard non-matches
                                .orElse(Key.SUBSYSTEM_SYMBOLICNAME.parseValues(feature)
                                        .findFirst()
                                        .map(v -> v.id)
                                        .map(pattern::matcher)
                                        .map(Matcher::matches)
                                        .orElse(false));
                    }

                    public boolean isStretchy() {
                        return false;
                    }

                    public String toString() {
                        return glob;
                    }
                };
        }
    }

    static String globToRegex(String glob) {
        try (var scanner = new Scanner(glob)) {
            var sb = new StringBuilder();
            scanner.useDelimiter("((?<=[?*])|(?=[?*]))"); // delimit immediately before and/or after a * or ?
            while (scanner.hasNext()) {
                String token = scanner.next();
                switch (token) {
                    case "?":
                        sb.append(".");
                        break;
                    case "*":
                        sb.append(".*");
                        break;
                    default:
                        sb.append(Pattern.quote(token));
                }
            }
            return sb.toString();
        }
    }
}
