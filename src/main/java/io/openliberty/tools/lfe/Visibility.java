package io.openliberty.tools.lfe;

import java.util.function.Predicate;
import java.util.jar.Attributes;

@SuppressWarnings("unused")
enum Visibility implements Predicate<Attributes> {
    PUBLIC,
    PROTECTED,
    PRIVATE,
    INSTALL,
    DEFAULT;

    static Visibility from(Attributes feature) {
        return Key.SUBSYSTEM_SYMBOLICNAME.parseValues(feature)
                .findFirst()
                .map(v -> v.getQualifier("visibility"))
                .map(String::toUpperCase)
                .map(Visibility::valueOf)
                .orElse(Visibility.DEFAULT);
    }

    String format(boolean tabs) {
        return String.format((tabs ? "%s" : "%-10s"), name().toLowerCase());
    }

    public boolean test(Attributes feature) {
        return this == from(feature);
    }
}
