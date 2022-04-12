package io.openliberty.tools.lfe;

import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Collections.unmodifiableMap;

class ValueElement {
    static final Pattern ATOM_PATTERN = Pattern.compile("(([^\";\\\\]|\\\\.)+|\"([^\\\\\"]|\\\\.)*+\")+");
    final String id;
    private final Map<? extends String, String> qualifiers;

    ValueElement(String text) {
        Matcher m = ATOM_PATTERN.matcher(text);
        if (!m.find()) throw new Error("Unable to parse manifest value into constituent parts: " + text);
        this.id = m.group();
        Map<String, String> map = new TreeMap<>();
        while (m.find(m.end())) {
            String[] parts = m.group().split(":?=", 2);
            if (null != map.put(parts[0].trim(), parts[1].trim().replaceFirst("^\"(.*)\"$", "$1")))
                // TODO: silence this warning if the ignore-duplicates flag has been passed
                System.err.printf("WARNING: duplicate metadata key '%s' detected in string '%s'", parts[0], text);
        }
        this.qualifiers = unmodifiableMap(map);
    }

    String getQualifier(String key) {
        return qualifiers.get(key);
    }

    public String toString() {
        return String.format("%88s : %s", id, qualifiers);
    }
}
