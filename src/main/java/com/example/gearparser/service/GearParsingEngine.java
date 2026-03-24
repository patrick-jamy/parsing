package com.example.gearparser.service;

import com.example.gearparser.model.GearItem;
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
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class GearParsingEngine {

    private static final Pattern GEAR_LABEL_PATTERN = Pattern.compile("(?i)\\bgear\\s*([0-9]{1,2}|[xiv]+)\\b");
    private static final Pattern ITEM_PATTERN = Pattern.compile("(?i)mk\\s*[0-9ivx]+|salvage|prototype|injector|furnace|medpac|keypad|stun");

    public ParseResult parse(String html) {
        Document document = Jsoup.parse(html);
        List<GearStat> bySections = parseByGearSections(document);
        if (!bySections.isEmpty()) {
            return new ParseResult(bySections, "dom-section-heuristics");
        }

        List<GearStat> byTableRows = parseByRows(document);
        return new ParseResult(byTableRows, "row-fallback-heuristics");
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

            String gear = Optional.ofNullable(normalizeGearLabel(text)).orElse("Gear inconnu");
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
                itemName)
                .toLowerCase(Locale.ROOT);

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
