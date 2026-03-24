package com.example.gearparser.service;

import com.example.gearparser.model.GearItem;
import com.example.gearparser.model.GearStat;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class GearParsingEngine {

    private static final Pattern GEAR_LABEL_PATTERN = Pattern.compile("(?i)\\bgear\\s*([0-9]{1,2}|[xiv]+)\\b");
    private static final Pattern GEAR_ANYWHERE_PATTERN = Pattern.compile("(?i)\\bgear\\W{0,8}([0-9]{1,2}|[xiv]+)\\b");
    private static final Pattern ITEM_PATTERN = Pattern.compile("(?i)mk\\s*[0-9ivx]+[^\\n\\r<>{}\\\"']{0,120}(?:salvage|prototype|component|furnace|stun gun|injector|medpac|keypad|implant|electrobinoculars?)");
    private static final Pattern TAG_TOKEN_PATTERN = Pattern.compile("<[^>]+>|[^<]+", Pattern.DOTALL);
    private static final Pattern TABLE_ROW_PATTERN = Pattern.compile("(?is)<tr[^>]*>\\s*<td[^>]*>(.*?)</td>\\s*<td[^>]*>(.*?)</td>");
    private static final Pattern SCRIPT_PATTERN = Pattern.compile("(?is)<script[^>]*>(.*?)</script>");
    private static final Pattern JSON_GEAR_AND_NAME_PATTERN = Pattern.compile(
            "(?is)(?:\\\"|')?(?:gear|gearLevel|gear_level|gearTier|gear_tier|tier|tierId|gear_id)(?:\\\"|')?\\s*:\\s*\\\"?([0-9]{1,2})\\\"?.{0,240}?"
                    + "(?:\\\"|')?(?:name|item_name|itemName|label|equipment|ingredient|title|desc|description)(?:\\\"|')?\\s*:\\s*\\\"([^\\\"]{2,180})\\\"");
    private static final Pattern JSON_NAME_AND_GEAR_PATTERN = Pattern.compile(
            "(?is)(?:\\\"|')?(?:name|item_name|itemName|label|equipment|ingredient|title|desc|description)(?:\\\"|')?\\s*:\\s*\\\"([^\\\"]{2,180})\\\".{0,240}?"
                    + "(?:\\\"|')?(?:gear|gearLevel|gear_level|gearTier|gear_tier|tier|tierId|gear_id)(?:\\\"|')?\\s*:\\s*\\\"?([0-9]{1,2})\\\"?");

    public ParseResult parse(String html) {
        Map<Integer, LinkedHashSet<GearItem>> merged = new TreeMap<>();
        merge(merged, parseFromTableRows(html));
        merge(merged, parseFromDomLikeContent(html));
        merge(merged, parseFromScriptSegments(html));
        merge(merged, parseFromStructuredJsonLikeContent(html));

        List<GearStat> stats = merged.entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .map(entry -> toStat("Gear " + entry.getKey(), new ArrayList<>(entry.getValue())))
                .sorted(Comparator.comparingInt(this::gearLevelFromLabel))
                .toList();

        return new ParseResult(stats, "multi-source-merged");
    }

    private void merge(Map<Integer, LinkedHashSet<GearItem>> target, Map<Integer, LinkedHashSet<GearItem>> source) {
        for (Map.Entry<Integer, LinkedHashSet<GearItem>> entry : source.entrySet()) {
            target.computeIfAbsent(entry.getKey(), ignored -> new LinkedHashSet<>()).addAll(entry.getValue());
        }
    }

    private Map<Integer, LinkedHashSet<GearItem>> parseFromTableRows(String html) {
        Map<Integer, LinkedHashSet<GearItem>> byGear = new TreeMap<>();
        Matcher matcher = TABLE_ROW_PATTERN.matcher(html);
        while (matcher.find()) {
            Integer gear = parseGearLevelText(stripTags(matcher.group(1)));
            String itemName = extractItemName(stripTags(matcher.group(2)));
            if (gear == null || itemName.isBlank()) {
                continue;
            }
            String context = matcher.group(0);
            byGear.computeIfAbsent(gear, ignored -> new LinkedHashSet<>())
                    .add(new GearItem(itemName, detectColorFromText(context + " " + itemName)));
        }
        return byGear;
    }

    private Map<Integer, LinkedHashSet<GearItem>> parseFromDomLikeContent(String html) {
        Map<Integer, LinkedHashSet<GearItem>> byGear = new TreeMap<>();
        Integer currentGear = null;
        String currentTag = "";

        Matcher tokenMatcher = TAG_TOKEN_PATTERN.matcher(html);
        while (tokenMatcher.find()) {
            String token = tokenMatcher.group();
            if (token.startsWith("<")) {
                currentTag = token.toLowerCase(Locale.ROOT);
                continue;
            }

            String text = normalizeSpace(token);
            if (text.isBlank()) {
                continue;
            }

            Integer maybeGear = parseGearLevelText(text);
            if (maybeGear != null) {
                currentGear = maybeGear;
            }

            String itemName = extractItemName(text);
            if (currentGear == null || itemName.isBlank()) {
                continue;
            }

            byGear.computeIfAbsent(currentGear, ignored -> new LinkedHashSet<>())
                    .add(new GearItem(itemName, detectColorFromText(currentTag + " " + itemName)));
        }

        return byGear;
    }

    private Map<Integer, LinkedHashSet<GearItem>> parseFromScriptSegments(String html) {
        Map<Integer, LinkedHashSet<GearItem>> byGear = new TreeMap<>();
        for (String script : extractScriptContents(html)) {
            List<GearMarker> markers = findGearMarkers(script);
            for (int i = 0; i < markers.size(); i++) {
                GearMarker marker = markers.get(i);
                int start = marker.position();
                int end = i + 1 < markers.size() ? markers.get(i + 1).position() : script.length();
                if (end <= start) {
                    continue;
                }
                String segment = script.substring(start, end);
                Matcher itemMatcher = ITEM_PATTERN.matcher(segment);
                while (itemMatcher.find()) {
                    String itemName = extractItemName(itemMatcher.group());
                    if (itemName.isBlank()) {
                        continue;
                    }
                    byGear.computeIfAbsent(marker.gear(), ignored -> new LinkedHashSet<>())
                            .add(new GearItem(itemName, detectColorFromText(segment + " " + itemName)));
                }
            }
        }
        return byGear;
    }

    private Map<Integer, LinkedHashSet<GearItem>> parseFromStructuredJsonLikeContent(String html) {
        Map<Integer, LinkedHashSet<GearItem>> byGear = new TreeMap<>();
        for (String script : extractScriptContents(html)) {
            Matcher directMatcher = JSON_GEAR_AND_NAME_PATTERN.matcher(script);
            while (directMatcher.find()) {
                Integer gear = parseGearLevelText(directMatcher.group(1));
                String itemName = extractItemName(directMatcher.group(2));
                if (gear != null && !itemName.isBlank()) {
                    byGear.computeIfAbsent(gear, ignored -> new LinkedHashSet<>())
                            .add(new GearItem(itemName, detectColorFromText(directMatcher.group())));
                }
            }

            Matcher reversedMatcher = JSON_NAME_AND_GEAR_PATTERN.matcher(script);
            while (reversedMatcher.find()) {
                String itemName = extractItemName(reversedMatcher.group(1));
                Integer gear = parseGearLevelText(reversedMatcher.group(2));
                if (gear != null && !itemName.isBlank()) {
                    byGear.computeIfAbsent(gear, ignored -> new LinkedHashSet<>())
                            .add(new GearItem(itemName, detectColorFromText(reversedMatcher.group())));
                }
            }

            parseTierBlocks(script, byGear);
        }
        return byGear;
    }

    private void parseTierBlocks(String script, Map<Integer, LinkedHashSet<GearItem>> byGear) {
        Matcher gearMatcher = Pattern.compile("(?is)\\\"gearLevel\\\"\\s*:\\s*([0-9]{1,2})").matcher(script);
        List<GearMarker> markers = new ArrayList<>();
        while (gearMatcher.find()) {
            markers.add(new GearMarker(Integer.parseInt(gearMatcher.group(1)), gearMatcher.start()));
        }
        for (int i = 0; i < markers.size(); i++) {
            GearMarker marker = markers.get(i);
            int start = marker.position();
            int end = i + 1 < markers.size() ? markers.get(i + 1).position() : script.length();
            String block = script.substring(start, end);
            Matcher nameMatcher = Pattern.compile("(?is)\\\"name\\\"\\s*:\\s*\\\"([^\\\"]{2,180})\\\"").matcher(block);
            while (nameMatcher.find()) {
                String itemName = extractItemName(nameMatcher.group(1));
                if (itemName.isBlank()) {
                    continue;
                }
                byGear.computeIfAbsent(marker.gear(), ignored -> new LinkedHashSet<>())
                        .add(new GearItem(itemName, detectColorFromText(block + " " + itemName)));
            }
        }
    }

    private List<String> extractScriptContents(String html) {
        List<String> scripts = new ArrayList<>();
        Matcher scriptMatcher = SCRIPT_PATTERN.matcher(html);
        while (scriptMatcher.find()) {
            scripts.add(scriptMatcher.group(1));
        }
        return scripts;
    }

    private List<GearMarker> findGearMarkers(String content) {
        List<GearMarker> markers = new ArrayList<>();
        Matcher matcher = GEAR_ANYWHERE_PATTERN.matcher(content);
        while (matcher.find()) {
            Integer level = parseGearLevelText(matcher.group(1));
            if (level != null) {
                markers.add(new GearMarker(level, matcher.start()));
            }
        }
        return markers;
    }

    private Integer parseGearLevelText(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String normalized = raw.trim();
        if (normalized.matches("[0-9]{1,2}")) {
            return Integer.parseInt(normalized);
        }
        if (normalized.matches("(?i)[XIV]{1,4}")) {
            return romanToInt(normalized.toUpperCase(Locale.ROOT));
        }

        Matcher gearMatcher = GEAR_LABEL_PATTERN.matcher(raw);
        if (gearMatcher.find()) {
            return parseGearLevelText(gearMatcher.group(1));
        }

        return null;
    }

    private String extractItemName(String rawText) {
        if (rawText == null) {
            return "";
        }
        String text = normalizeSpace(stripTags(rawText));
        if (text.length() > 180) {
            text = text.substring(0, 180);
        }

        Matcher matcher = ITEM_PATTERN.matcher(text);
        if (matcher.find()) {
            return normalizeSpace(matcher.group());
        }
        return "";
    }

    private String detectColorFromText(String input) {
        String aggregated = Optional.ofNullable(input).orElse("").toLowerCase(Locale.ROOT);
        if (containsAny(aggregated, "orange", "gold", "amber", "legendary")) return "orange";
        if (containsAny(aggregated, "blue", "bleu", "rare")) return "bleu";
        if (containsAny(aggregated, "purple", "epic", "violet")) return "violet";
        if (containsAny(aggregated, "green", "vert", "uncommon")) return "vert";
        if (containsAny(aggregated, "grey", "gray", "common", "basic")) return "gris";
        if (aggregated.contains("prototype")) return "orange";
        return "inconnu";
    }

    private boolean containsAny(String input, String... values) {
        for (String value : values) {
            if (input.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private int gearLevelFromLabel(GearStat stat) {
        return gearLevelFromLabel(stat.gearLabel());
    }

    private int gearLevelFromLabel(String label) {
        Matcher matcher = Pattern.compile("([0-9]{1,2}|[XIV]+)").matcher(label.toUpperCase(Locale.ROOT));
        if (!matcher.find()) {
            return Integer.MAX_VALUE;
        }
        String value = matcher.group(1);
        if (value.matches("\\d+")) {
            return Integer.parseInt(value);
        }
        return romanToInt(value);
    }

    private int romanToInt(String roman) {
        Map<Character, Integer> values = Map.of('I', 1, 'V', 5, 'X', 10, 'L', 50, 'C', 100);
        int total = 0;
        for (int i = 0; i < roman.length(); i++) {
            int current = values.getOrDefault(roman.charAt(i), 0);
            int next = (i + 1 < roman.length()) ? values.getOrDefault(roman.charAt(i + 1), 0) : 0;
            total += current < next ? -current : current;
        }
        return total;
    }

    private GearStat toStat(String gearLabel, List<GearItem> items) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (GearItem item : items) {
            counts.merge(item.color(), 1, Integer::sum);
        }
        return new GearStat(gearLabel, items.size(), counts, items);
    }

    private String stripTags(String input) {
        return input.replaceAll("(?is)<[^>]*>", " ");
    }

    private String normalizeSpace(String input) {
        return input.replaceAll("\\s+", " ").trim();
    }

    private record GearMarker(int gear, int position) {}

    public record ParseResult(List<GearStat> stats, String strategy) {
    }
}
