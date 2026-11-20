package com.mcworldexplorer.ui;

import java.util.regex.Pattern;

record BlockCoordinates(int x, int z) {
    private static final Pattern PAIR = Pattern.compile("\\s*([+-]?\\d+)\\s*,\\s*([+-]?\\d+)\\s*");

    static String format(long x, long z) {
        return x + ", " + z;
    }

    static boolean isPair(String text) {
        return PAIR.matcher(text).matches();
    }

    static BlockCoordinates parse(String text) {
        var match = PAIR.matcher(text);
        if (!match.matches()) throw new IllegalArgumentException("请按 X, Z 格式输入两个整数，例如 -16, 24");
        try {
            return new BlockCoordinates(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)));
        } catch (NumberFormatException outOfRange) {
            throw new IllegalArgumentException("方块坐标超出 32 位整数范围");
        }
    }
}
