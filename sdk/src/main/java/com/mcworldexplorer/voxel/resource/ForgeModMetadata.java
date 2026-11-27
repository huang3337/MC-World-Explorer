package com.mcworldexplorer.voxel.resource;

import java.util.Set;
import java.util.regex.Pattern;

/** Extracts primary IDs only from mods array tables in bounded Forge-family TOML manifests. */
final class ForgeModMetadata {
    private static final Pattern MOD_TABLE = Pattern.compile("\\[\\[\\s*mods\\s*]]\\s*(?:#.*)?");
    private static final Pattern MOD_ID = Pattern.compile("modId\\s*=\\s*([\"'])([a-z0-9_.-]{1,64})\\1\\s*(?:#.*)?");

    private ForgeModMetadata() {}

    static void collect(String toml, Set<String> owners) {
        if (toml == null) return;
        boolean inMods = false;
        char multiline = 0;
        for (var lines = toml.lines().iterator(); lines.hasNext();) {
            String line = lines.next().strip();
            if (multiline == 0) {
                if (line.startsWith("[")) inMods = MOD_TABLE.matcher(line).matches();
                else if (inMods && line.startsWith("modId")) {
                    var id = MOD_ID.matcher(line);
                    if (id.matches()) owners.add(id.group(2));
                }
            }
            multiline = multilineAfter(line, multiline);
        }
    }

    // Text inside a multiline description may itself look like a table, comment or modId.
    private static char multilineAfter(String line, char multiline) {
        char quoted = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && (multiline == '"' || quoted == '"')) {
                i++;
            } else if (multiline != 0) {
                if (c == multiline && triple(line, i, c)) {
                    while (i + 1 < line.length() && line.charAt(i + 1) == c) i++;
                    multiline = 0;
                }
            } else if (quoted != 0) {
                if (c == quoted) quoted = 0;
            } else if (c == '#') {
                break;
            } else if (c == '"' || c == '\'') {
                if (triple(line, i, c)) {
                    multiline = c;
                    i += 2;
                } else {
                    quoted = c;
                }
            }
        }
        return multiline;
    }

    private static boolean triple(String line, int i, char c) {
        return i + 2 < line.length() && line.charAt(i + 1) == c && line.charAt(i + 2) == c;
    }
}
