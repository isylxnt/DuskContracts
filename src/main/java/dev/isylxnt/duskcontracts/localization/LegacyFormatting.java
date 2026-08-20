package dev.isylxnt.duskcontracts.localization;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LegacyFormatting {
    private static final Pattern HEX = Pattern.compile("(?i)&#([0-9a-f]{6})");
    private static final Pattern CODE = Pattern.compile("(?i)&([0-9a-fk-or])");
    private static final Map<Character, String> TAGS = Map.ofEntries(
            Map.entry('0', "black"), Map.entry('1', "dark_blue"), Map.entry('2', "dark_green"),
            Map.entry('3', "dark_aqua"), Map.entry('4', "dark_red"), Map.entry('5', "dark_purple"),
            Map.entry('6', "gold"), Map.entry('7', "gray"), Map.entry('8', "dark_gray"),
            Map.entry('9', "blue"), Map.entry('a', "green"), Map.entry('b', "aqua"),
            Map.entry('c', "red"), Map.entry('d', "light_purple"), Map.entry('e', "yellow"),
            Map.entry('f', "white"), Map.entry('k', "obfuscated"), Map.entry('l', "bold"),
            Map.entry('m', "strikethrough"), Map.entry('n', "underlined"), Map.entry('o', "italic"),
            Map.entry('r', "reset"));

    private LegacyFormatting() { }

    static String normalize(String input) {
        String normalized = HEX.matcher(input).replaceAll("<#$1>");
        Matcher matcher = CODE.matcher(normalized);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            char code = Character.toLowerCase(matcher.group(1).charAt(0));
            matcher.appendReplacement(output, Matcher.quoteReplacement("<" + TAGS.get(code) + ">"));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    static String normalize(String input, Iterable<String> placeholders) {
        String normalized = normalize(input);
        for (String placeholder : placeholders) {
            normalized = normalized.replace("{" + placeholder + "}", "<" + placeholder + ">");
        }
        return normalized;
    }
}
