package com.example.gearparser.model;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

public record ParseResponse(String sourceUrl, Instant refreshedAt, List<GearStat> gears, String parserStrategy) {

    public ParseResponse {
        gears = Collections.unmodifiableList(gears);
    }
}
