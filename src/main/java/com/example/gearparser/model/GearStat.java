package com.example.gearparser.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public record GearStat(String gearLabel, int totalItems, Map<String, Integer> colorCounts, List<GearItem> items) {

    public GearStat {
        colorCounts = Collections.unmodifiableMap(colorCounts);
        items = Collections.unmodifiableList(items);
    }
}
