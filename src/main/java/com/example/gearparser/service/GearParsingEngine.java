package com.example.gearparser.service;

import com.example.gearparser.model.GearItem;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.example.gearparser.model.GearStat;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Iterator;
import java.util.Map.Entry;
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
    private static final Pattern ITEM_PATTERN = Pattern.compile("(?i)mk\\s*[0-9ivx]+|salvage|prototype|injector|furnace|medpac|keypad|stun");
    private static final Pattern SCRIPT_OBJECT_PATTERN = Pattern.compile("\\{[^{}]{0,500}\\}");
    private static final Pattern SCRIPT_GEAR_FIELD_PATTERN = Pattern.compile("(?i)(?:gear_level|gearLevel|gearTier|gear_tier|tier|gear)\\s*[:=]\\s*['\\\"]?([0-9]{1,2}|[xiv]+)");
    private static final Pattern SCRIPT_ITEM_FIELD_PATTERN = Pattern.compile("(?i)(?:name|item_name|itemName|label|equipment|ingredient)\\s*[:=]\\s*['\\\"]([^'\\\"]{3,140})['\\\"]");

    public ParseResult parse(String html) {
        Document document = Jsoup.parse(html);
        List<GearStat> byStructuredJson = parseByStructuredJson(document);
        List<GearStat> bySections = parseByGearSections(document);
        List<GearStat> byTableRows = parseByRows(document);
        List<GearStat> byDataAttributes = parseByDataAttributes(document);
        List<GearStat> byEmbeddedData = parseByEmbeddedScripts(document);

        return pickBest(
                new ParseResult(byStructuredJson, "structured-json-graph-walk"),
                new ParseResult(bySections, "dom-section-heuristics"),
                new ParseResult(byTableRows, "row-fallback-heuristics"),
                new ParseResult(byDataAttributes, "dom-data-attributes-heuristics"),
                new ParseResult(byEmbeddedData, "embedded-script-heuristics")
        );
    }



    private List<GearStat> parseByStructuredJson(Document document) {
        Map<Integer, List<GearItem>> byGear = new TreeMap<>();

        for (Element script : document.select("script")) {
            String scriptContent = script.data();
            if (scriptContent == null || scriptContent.isBlank()) {
                continue;
            }

            for (String jsonCandidate : extractJsonCandidates(scriptContent)) {
                try {
                    JsonNode root = objectMapper.readTree(jsonCandidate);
                    collectGearItemsFromJson(root, null, byGear);
                } catch (Exception ignored) {
                    // non-JSON script fragment; continue scanning
                }
            }
        }

        return byGear.entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .map(entry -> toStat("Gear " + entry.getKey(), entry.getValue()))
                .toList();
    }

    private List<String> extractJsonCandidates(String scriptContent) {
        List<String> candidates = new ArrayList<>();

        for (int i = 0; i < scriptContent.length(); i++) {
            char current = scriptContent.charAt(i);
            if (current != '=') {
                continue;
            }

            int start = findNextJsonStart(scriptContent, i + 1);
            if (start < 0) {
                continue;
            }

            String candidate = readBalancedJson(scriptContent, start);
            if (!candidate.isBlank()) {
                candidates.add(candidate);
                i = start + candidate.length() - 1;
            }
        }

        String trimmed = scriptContent.trim();
        if ((trimmed.startsWith("{") && trimmed.endsWith("}"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            candidates.add(trimmed);
        }

        return candidates;
    }

    private int findNextJsonStart(String scriptContent, int fromIndex) {
        for (int i = fromIndex; i < scriptContent.length(); i++) {
            char ch = scriptContent.charAt(i);
            if (Character.isWhitespace(ch)) {
                continue;
            }
            if (ch == '{' || ch == '[') {
                return i;
            }
            return -1;
        }
        return -1;
    }

    private String readBalancedJson(String scriptContent, int startIndex) {
        char opening = scriptContent.charAt(startIndex);
        char closing = opening == '{' ? '}' : ']';
        int depth = 0;
        boolean inString = false;
        char stringDelimiter = 0;
        boolean escaped = false;

        for (int i = startIndex; i < scriptContent.length(); i++) {
            char ch = scriptContent.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (ch == '\\') {
                    escaped = true;
                    continue;
                }
                if (ch == stringDelimiter) {
                    inString = false;
                }
                continue;
            }

            if (ch == '"' || ch == '\'') {
                inString = true;
                stringDelimiter = ch;
                continue;
            }

            if (ch == opening) {
                depth++;
            } else if (ch == closing) {
                depth--;
                if (depth == 0) {
                    return scriptContent.substring(startIndex, i + 1);
                }
            }
        }

        return "";
    }

    private void collectGearItemsFromJson(JsonNode node, Integer inheritedGear, Map<Integer, List<GearItem>> byGear) {
        if (node == null || node.isNull()) {
            return;
        }

        if (node.isArray()) {
            for (JsonNode child : node) {
                collectGearItemsFromJson(child, inheritedGear, byGear);
            }
            return;
        }

        if (!node.isObject()) {
            return;
        }

        Integer localGear = extractGearLevelFromJson(node).orElse(inheritedGear);

        Optional<String> localItemName = extractItemNameFromJsonNode(node);
        if (localGear != null && localItemName.isPresent()) {
            String itemName = extractItemName(localItemName.get());
            if (!itemName.isBlank()) {
                byGear.computeIfAbsent(localGear, ignored -> new ArrayList<>())
                        .add(new GearItem(itemName, detectColorFromText(node.toString() + " " + itemName)));
            }
        }

        Iterator<Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Entry<String, JsonNode> field = fields.next();
            collectGearItemsFromJson(field.getValue(), localGear, byGear);
        }
    }

    private Optional<Integer> extractGearLevelFromJson(JsonNode node) {
        for (String field : List.of("gear", "gearLevel", "gear_level", "gearTier", "gear_tier", "tier", "tierId", "gear_id")) {
            if (!node.has(field)) {
                continue;
            }
            JsonNode value = node.get(field);
            Integer parsed = parseGearLevelText(value.isNumber() ? value.asText() : value.asText(""));
            if (parsed != null) {
                return Optional.of(parsed);
            }
        }
        return Optional.empty();
    }

    private Optional<String> extractItemNameFromJsonNode(JsonNode node) {
        for (String field : List.of("name", "item_name", "itemName", "label", "equipment", "ingredient", "title", "desc", "description")) {
            if (!node.has(field)) {
                continue;
            }
            String value = node.get(field).asText("");
            if (!value.isBlank()) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    private Integer parseGearLevelText(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        Matcher numericMatcher = Pattern.compile("([0-9]{1,2})").matcher(raw);
        if (numericMatcher.find()) {
            return Integer.parseInt(numericMatcher.group(1));
        }

        Matcher romanMatcher = Pattern.compile("\\b([XIV]{1,4})\\b", Pattern.CASE_INSENSITIVE).matcher(raw);
        if (romanMatcher.find()) {
            return romanToInt(romanMatcher.group(1).toUpperCase(Locale.ROOT));
        }

        return null;
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
    private List<GearStat> parseByGearSections(Document document) {
        List<Element> headings = document.select("h1, h2, h3, h4, h5, .card-title, .section-title, strong").stream()
                .filter(el -> GEAR_LABEL_PATTERN.matcher(el.text()).find())
                .toList();

        List<GearStat> stats = new ArrayList<>();
        for (Element heading : headings) {
            String gear = normalizeGearLabel(heading.text());
            if (gear == null) {
                continue;
            }
            List<GearItem> items = extractItemsFromSection(heading);
            if (!items.isEmpty()) {
                stats.add(toStat(gear, items));
            }
        }

        return stats.stream()
                .sorted(Comparator.comparingInt(this::gearLevelFromLabel))
                .toList();
    }

    private List<GearStat> parseByRows(Document document) {
        Elements rows = document.select("tr, li, .media, .list-group-item, .collection-item");
        Map<String, List<GearItem>> byGear = new LinkedHashMap<>();

        for (Element row : rows) {
            String text = row.text();
            if (!ITEM_PATTERN.matcher(text).find()) {
                continue;
            }

            String gear = Optional.ofNullable(inferGearLabel(row)).orElse("Gear inconnu");
            String itemName = extractItemName(text);
            if (itemName.isBlank()) {
                continue;
            }

            String color = detectColor(row, itemName);
            byGear.computeIfAbsent(gear, key -> new ArrayList<>())
                    .add(new GearItem(itemName, color));
        }

        return byGear.entrySet().stream()
                .map(entry -> toStat(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(this::gearLevelFromLabel))
                .toList();
    }

    private List<GearStat> parseByEmbeddedScripts(Document document) {
        Map<String, List<GearItem>> byGear = new LinkedHashMap<>();

        for (Element script : document.select("script")) {
            String scriptContent = script.data();
            if (scriptContent == null || scriptContent.isBlank()) {
                continue;
            }

            String currentGear = null;
            Matcher gearMatcher = Pattern.compile("(?i)gear\\W{0,5}([0-9]{1,2}|[xiv]+)").matcher(scriptContent);
            while (gearMatcher.find()) {
                currentGear = "Gear " + gearMatcher.group(1).toUpperCase(Locale.ROOT);
                byGear.putIfAbsent(currentGear, new ArrayList<>());

                int windowStart = gearMatcher.end();
                int windowEnd = Math.min(scriptContent.length(), windowStart + 1200);
                String window = scriptContent.substring(windowStart, windowEnd);

                Matcher itemMatcher = Pattern.compile("(?i)(mk\\s*[0-9ivx][^\"\\n\\r]{2,90}(?:salvage|prototype|component|furnace|stun gun|injector|medpac|keypad))")
                        .matcher(window);
                while (itemMatcher.find()) {
                    String itemName = extractItemName(itemMatcher.group(1));
                    if (!itemName.isBlank()) {
                        byGear.get(currentGear).add(new GearItem(itemName, detectColor(script, itemName)));
                    }
                }
            }

            Matcher objectMatcher = SCRIPT_OBJECT_PATTERN.matcher(scriptContent);
            while (objectMatcher.find()) {
                String objectChunk = objectMatcher.group();
                String gear = normalizeGearLabelFromScriptChunk(objectChunk);
                if (gear == null) {
                    continue;
                }
                String itemName = extractItemNameFromScriptChunk(objectChunk);
                if (itemName.isBlank()) {
                    continue;
                }
                byGear.computeIfAbsent(gear, ignored -> new ArrayList<>())
                        .add(new GearItem(itemName, detectColor(script, itemName)));
            }
        }

        return byGear.entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .map(entry -> toStat(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(this::gearLevelFromLabel))
                .toList();
    }

    private List<GearStat> parseByDataAttributes(Document document) {
        Elements candidates = document.select("[data-gear], [data-gear-level], [data-tier], [data-name], [title], [alt], a, span, div");
        Map<String, List<GearItem>> byGear = new LinkedHashMap<>();

        for (Element candidate : candidates) {
            String itemText = String.join(" ",
                    candidate.ownText(),
                    candidate.attr("data-name"),
                    candidate.attr("title"),
                    candidate.attr("alt")).trim();
            String itemName = extractItemName(itemText);
            if (itemName.isBlank()) {
                continue;
            }

            String gear = Optional.ofNullable(normalizeGearLabel(candidate.attr("data-gear")))
                    .or(() -> Optional.ofNullable(normalizeGearLabel(candidate.attr("data-gear-level"))))
                    .or(() -> Optional.ofNullable(normalizeGearLabel(candidate.attr("data-tier"))))
                    .or(() -> Optional.ofNullable(inferGearLabel(candidate)))
                    .orElse("Gear inconnu");

            byGear.computeIfAbsent(gear, ignored -> new ArrayList<>())
                    .add(new GearItem(itemName, detectColor(candidate, itemName)));
        }

        return byGear.entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .map(entry -> toStat(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(this::gearLevelFromLabel))
                .toList();
    }

    private String normalizeGearLabelFromScriptChunk(String chunk) {
        Matcher matcher = SCRIPT_GEAR_FIELD_PATTERN.matcher(chunk);
        if (!matcher.find()) {
            return null;
        }
        return "Gear " + matcher.group(1).toUpperCase(Locale.ROOT);
    }

    private String extractItemNameFromScriptChunk(String chunk) {
        Matcher matcher = SCRIPT_ITEM_FIELD_PATTERN.matcher(chunk);
        if (!matcher.find()) {
            return "";
        }
        return extractItemName(matcher.group(1));
    }

    private String inferGearLabel(Element row) {
        String direct = normalizeGearLabel(row.text());
        if (direct != null) {
            return direct;
        }

        Element cursor = row;
        while (cursor != null) {
            Element sibling = cursor.previousElementSibling();
            while (sibling != null) {
                String fromSibling = normalizeGearLabel(sibling.text());
                if (fromSibling != null) {
                    return fromSibling;
                }

                Element nestedHeading = sibling.selectFirst("h1, h2, h3, h4, h5, h6");
                if (nestedHeading != null) {
                    String fromNestedHeading = normalizeGearLabel(nestedHeading.text());
                    if (fromNestedHeading != null) {
                        return fromNestedHeading;
                    }
                }
                sibling = sibling.previousElementSibling();
            }
            cursor = cursor.parent();
        }

        return null;
    }

    private ParseResult pickBest(ParseResult... candidates) {
        ParseResult best = new ParseResult(List.of(), "no-data");
        for (ParseResult candidate : candidates) {
            if (isBetter(candidate, best)) {
                best = candidate;
            }
        }
        return best;
    }

    private boolean isBetter(ParseResult candidate, ParseResult currentBest) {
        int candidateGears = candidate.stats().size();
        int currentGears = currentBest.stats().size();
        if (candidateGears != currentGears) {
            return candidateGears > currentGears;
        }

        int candidateItems = candidate.stats().stream().mapToInt(GearStat::totalItems).sum();
        int currentItems = currentBest.stats().stream().mapToInt(GearStat::totalItems).sum();
        return candidateItems > currentItems;
    }

    private List<GearItem> extractItemsFromSection(Element heading) {
        List<GearItem> items = new ArrayList<>();
        Element cursor = heading;
        int maxNodes = 120;

        while (cursor != null && maxNodes-- > 0) {
            cursor = cursor.nextElementSibling();
            if (cursor == null) {
                break;
            }
            if (GEAR_LABEL_PATTERN.matcher(cursor.text()).find() && cursor.tagName().matches("h[1-6]")) {
                break;
            }

            for (Element candidate : cursor.select("li, tr, .item, .media, .collection-item, .eq-item, .gear-item, a")) {
                String itemName = extractItemName(candidate.text());
                if (!itemName.isBlank()) {
                    items.add(new GearItem(itemName, detectColor(candidate, itemName)));
                }
            }
        }

        return items.stream().filter(item -> ITEM_PATTERN.matcher(item.name()).find()).toList();
    }

    private GearStat toStat(String gearLabel, List<GearItem> items) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (GearItem item : items) {
            counts.merge(item.color(), 1, Integer::sum);
        }
        return new GearStat(gearLabel, items.size(), counts, items);
    }

    private String detectColor(Element source, String itemName) {
        String aggregated = String.join(" ",
                source.className(),
                source.attr("style"),
                Optional.ofNullable(source.selectFirst("img")).map(img -> img.className() + " " + img.attr("style") + " " + img.attr("alt")).orElse(""),
                itemName);
        return detectColorFromText(aggregated);
    }

    private boolean containsAny(String input, String... values) {
        for (String value : values) {
            if (input.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeGearLabel(String text) {
        Matcher matcher = GEAR_LABEL_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return "Gear " + matcher.group(1).toUpperCase(Locale.ROOT);
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
        Map<Character, Integer> values = Map.of(
                'I', 1,
                'V', 5,
                'X', 10,
                'L', 50,
                'C', 100
        );

        int total = 0;
        for (int i = 0; i < roman.length(); i++) {
            int current = Objects.requireNonNullElse(values.get(roman.charAt(i)), 0);
            int next = (i + 1 < roman.length()) ? Objects.requireNonNullElse(values.get(roman.charAt(i + 1)), 0) : 0;
            total += current < next ? -current : current;
        }
        return total;
    }

    private String extractItemName(String rawText) {
        if (rawText == null) {
            return "";
        }
        String text = rawText.replaceAll("\\s+", " ").trim();
        if (text.length() > 160) {
            text = text.substring(0, 160);
        }
        return ITEM_PATTERN.matcher(text).find() ? text : "";
    }

    public record ParseResult(List<GearStat> stats, String strategy) {
    }
}
