package com.example.gearparser.service;

import com.example.gearparser.model.GearItem;
import com.example.gearparser.model.GearStat;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class GearParsingEngine {

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();

    private static final Pattern GEAR_LABEL_PATTERN = Pattern.compile("(?i)\\bgear\\s*([0-9]{1,2}|[xiv]+)\\b");
    private static final Pattern GEAR_ANYWHERE_PATTERN = Pattern.compile("(?i)\\bgear\\W{0,8}([0-9]{1,2}|[xiv]+)\\b");
    private static final Pattern ITEM_PATTERN = Pattern.compile("(?i)mk\\s*[0-9ivx]+|salvage|prototype|injector|furnace|medpac|keypad|stun|implant|electrobinocular|holo|carbanti");
    private static final Pattern SCRIPT_ITEM_PATTERN = Pattern.compile("(?i)(mk\\s*[0-9ivx][^\\n\\r\"']{1,120}(?:salvage|prototype|component|furnace|stun gun|injector|medpac|keypad|implant|electrobinoculars?))");

    public ParseResult parse(String html) {
        Document document = Jsoup.parse(html);

        Map<Integer, LinkedHashSet<GearItem>> merged = new TreeMap<>();
        merge(merged, parseByStructuredJson(document));
        merge(merged, parseByDomTraversal(document));
        merge(merged, parseByScriptSegments(document));
        merge(merged, parseByDataAttributes(document));

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

    private Map<Integer, LinkedHashSet<GearItem>> parseByStructuredJson(Document document) {
        Map<Integer, LinkedHashSet<GearItem>> byGear = new TreeMap<>();
        for (Element script : document.select("script")) {
            String content = script.data();
            if (content == null || content.isBlank()) {
                continue;
            }

            for (String candidate : extractJsonCandidates(content)) {
                try {
                    JsonNode root = objectMapper.readTree(candidate);
                    collectGearItemsFromJson(root, null, byGear);
                } catch (Exception ignored) {
                    // ignore invalid chunks
                }
            }
        }
        return byGear;
    }

    private List<String> extractJsonCandidates(String scriptContent) {
        List<String> candidates = new ArrayList<>();
        for (int i = 0; i < scriptContent.length(); i++) {
            if (scriptContent.charAt(i) != '=') {
                continue;
            }
            int start = findNextJsonStart(scriptContent, i + 1);
            if (start < 0) {
                continue;
            }
            String candidate = readBalancedJson(scriptContent, start);
            if (!candidate.isBlank()) {
                candidates.add(candidate);
            }
        }

        String trimmed = scriptContent.trim();
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            candidates.add(trimmed);
        }
        return candidates;
    }

    private int findNextJsonStart(String content, int fromIndex) {
        for (int i = fromIndex; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (Character.isWhitespace(ch)) {
                continue;
            }
            return (ch == '{' || ch == '[') ? i : -1;
        }
        return -1;
    }

    private String readBalancedJson(String content, int startIndex) {
        char opening = content.charAt(startIndex);
        char closing = opening == '{' ? '}' : ']';
        int depth = 0;
        boolean inString = false;
        char delimiter = 0;
        boolean escaped = false;

        for (int i = startIndex; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (ch == '\\') {
                    escaped = true;
                    continue;
                }
                if (ch == delimiter) {
                    inString = false;
                }
                continue;
            }

            if (ch == '\'' || ch == '"') {
                inString = true;
                delimiter = ch;
                continue;
            }

            if (ch == opening) {
                depth++;
            } else if (ch == closing) {
                depth--;
                if (depth == 0) {
                    return content.substring(startIndex, i + 1);
                }
            }
        }

        return "";
    }

    private void collectGearItemsFromJson(JsonNode node, Integer inheritedGear, Map<Integer, LinkedHashSet<GearItem>> byGear) {
        if (node == null || node.isNull()) {
            return;
        }

        if (node.isArray()) {
            node.forEach(child -> collectGearItemsFromJson(child, inheritedGear, byGear));
            return;
        }

        if (!node.isObject()) {
            return;
        }

        Integer localGear = extractGearLevelFromJson(node).orElse(inheritedGear);
        Optional<String> itemName = extractItemNameFromJsonNode(node).map(this::extractItemName).filter(s -> !s.isBlank());

        if (localGear != null && itemName.isPresent()) {
            byGear.computeIfAbsent(localGear, ignored -> new LinkedHashSet<>())
                    .add(new GearItem(itemName.get(), detectColorFromText(node.toString() + " " + itemName.get())));
        }

        node.fields().forEachRemaining(entry -> collectGearItemsFromJson(entry.getValue(), localGear, byGear));
    }

    private Optional<Integer> extractGearLevelFromJson(JsonNode node) {
        for (String field : List.of("gear", "gearLevel", "gear_level", "gearTier", "gear_tier", "tier", "tierId", "gear_id")) {
            if (!node.has(field)) {
                continue;
            }
            Integer parsed = parseGearLevelText(node.get(field).asText(""));
            if (parsed != null) {
                return Optional.of(parsed);
            }
        }
        return Optional.empty();
    }

    private Optional<String> extractItemNameFromJsonNode(JsonNode node) {
        for (String field : List.of("name", "item_name", "itemName", "label", "equipment", "ingredient", "title", "desc", "description")) {
            if (node.has(field)) {
                String value = node.get(field).asText("");
                if (!value.isBlank()) {
                    return Optional.of(value);
                }
            }
        }
        return Optional.empty();
    }

    private Map<Integer, LinkedHashSet<GearItem>> parseByDomTraversal(Document document) {
        Map<Integer, LinkedHashSet<GearItem>> byGear = new TreeMap<>();

        Elements candidates = document.select("li, tr, .item, .media, .collection-item, .eq-item, .gear-item, a, div, span, p");
        Integer currentGear = null;

        for (Element el : candidates) {
            Integer detected = detectGearFromElementContext(el);
            if (detected != null) {
                currentGear = detected;
            }

            String itemName = extractItemName(el.text());
            if (itemName.isBlank()) {
                continue;
            }

            Integer gearForItem = Optional.ofNullable(detected)
                    .or(() -> Optional.ofNullable(currentGear))
                    .or(() -> Optional.ofNullable(parseGearLevelText(el.closest("section, article, table, ul, ol, div") != null ? el.closest("section, article, table, ul, ol, div").text() : "")))
                    .orElse(null);

            if (gearForItem == null) {
                continue;
            }

            byGear.computeIfAbsent(gearForItem, ignored -> new LinkedHashSet<>())
                    .add(new GearItem(itemName, detectColor(el, itemName)));
        }

        return byGear;
    }

    private Map<Integer, LinkedHashSet<GearItem>> parseByScriptSegments(Document document) {
        Map<Integer, LinkedHashSet<GearItem>> byGear = new TreeMap<>();

        for (Element script : document.select("script")) {
            String content = script.data();
            if (content == null || content.isBlank()) {
                continue;
            }

            List<GearMarker> markers = findGearMarkers(content);
            for (int i = 0; i < markers.size(); i++) {
                GearMarker marker = markers.get(i);
                int start = marker.position();
                int end = i + 1 < markers.size() ? markers.get(i + 1).position() : content.length();
                if (end <= start) {
                    continue;
                }

                String segment = content.substring(start, end);
                Matcher itemMatcher = SCRIPT_ITEM_PATTERN.matcher(segment);
                while (itemMatcher.find()) {
                    String itemName = extractItemName(itemMatcher.group(1));
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

    private Map<Integer, LinkedHashSet<GearItem>> parseByDataAttributes(Document document) {
        Map<Integer, LinkedHashSet<GearItem>> byGear = new TreeMap<>();
        Elements nodes = document.select("[data-gear], [data-gear-level], [data-tier], [data-name], [title], [alt]");

        for (Element node : nodes) {
            Integer gear = Optional.ofNullable(parseGearLevelText(node.attr("data-gear")))
                    .or(() -> Optional.ofNullable(parseGearLevelText(node.attr("data-gear-level"))))
                    .or(() -> Optional.ofNullable(parseGearLevelText(node.attr("data-tier"))))
                    .orElseGet(() -> detectGearFromElementContext(node));

            if (gear == null) {
                continue;
            }

            String item = extractItemName(String.join(" ", node.ownText(), node.attr("data-name"), node.attr("title"), node.attr("alt")));
            if (item.isBlank()) {
                continue;
            }

            byGear.computeIfAbsent(gear, ignored -> new LinkedHashSet<>())
                    .add(new GearItem(item, detectColor(node, item)));
        }

        return byGear;
    }

    private Integer detectGearFromElementContext(Element element) {
        Integer direct = parseGearLevelText(element.text());
        if (direct != null) {
            return direct;
        }

        Element cursor = element;
        while (cursor != null) {
            Element sibling = cursor.previousElementSibling();
            while (sibling != null) {
                Integer fromSibling = parseGearLevelText(sibling.text());
                if (fromSibling != null) {
                    return fromSibling;
                }
                sibling = sibling.previousElementSibling();
            }
            cursor = cursor.parent();
        }
        return null;
    }

    private Integer parseGearLevelText(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        Matcher gearMatcher = GEAR_LABEL_PATTERN.matcher(raw);
        if (gearMatcher.find()) {
            return parseGearLevelText(gearMatcher.group(1));
        }

        Matcher numericMatcher = Pattern.compile("\\b([0-9]{1,2})\\b").matcher(raw);
        if (numericMatcher.find()) {
            return Integer.parseInt(numericMatcher.group(1));
        }

        Matcher romanMatcher = Pattern.compile("\\b([XIV]{1,4})\\b", Pattern.CASE_INSENSITIVE).matcher(raw);
        if (romanMatcher.find()) {
            return romanToInt(romanMatcher.group(1).toUpperCase(Locale.ROOT));
        }

        return null;
    }

    private String extractItemName(String rawText) {
        if (rawText == null) {
            return "";
        }
        String text = rawText.replaceAll("\\s+", " ").trim();
        if (text.length() > 180) {
            text = text.substring(0, 180);
        }
        return ITEM_PATTERN.matcher(text).find() ? text : "";
    }

    private String detectColor(Element source, String itemName) {
        String aggregated = String.join(" ",
                source.className(),
                source.attr("style"),
                Optional.ofNullable(source.selectFirst("img")).map(img -> img.className() + " " + img.attr("style") + " " + img.attr("alt")).orElse(""),
                itemName);
        return detectColorFromText(aggregated);
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
            int current = Objects.requireNonNullElse(values.get(roman.charAt(i)), 0);
            int next = (i + 1 < roman.length()) ? Objects.requireNonNullElse(values.get(roman.charAt(i + 1)), 0) : 0;
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

    private record GearMarker(int gear, int position) {}

    public record ParseResult(List<GearStat> stats, String strategy) {
    }
}
