package com.example.gearparser.service;

import com.example.gearparser.model.GearStat;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GearParsingEngineTest {

    private final GearParsingEngine engine = new GearParsingEngine();

    @Test
    void shouldParseGearSectionsAndDetectColors() throws Exception {
        String html = Files.readString(Path.of("src/test/resources/sample-gear-page.html"));

        GearParsingEngine.ParseResult result = engine.parse(html);
        List<GearStat> stats = result.stats();

        assertThat(result.strategy()).isEqualTo("dom-section-heuristics");
        assertThat(stats).hasSize(2);

        GearStat gear8 = stats.getFirst();
        assertThat(gear8.gearLabel()).isEqualTo("Gear 8");
        assertThat(gear8.totalItems()).isEqualTo(3);
        assertThat(gear8.colorCounts()).containsEntry("bleu", 1).containsEntry("orange", 1);

        GearStat gear9 = stats.get(1);
        assertThat(gear9.gearLabel()).isEqualTo("Gear 9");
        assertThat(gear9.totalItems()).isEqualTo(2);
    }
}
