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

    @Test
    void shouldPreferStrategyWithMoreDetectedGears() {
        String html = """
                <html><body>
                <h2>Gear 8</h2>
                <ul><li class="gear-item blue">Mk 5 A/KT Stun Gun Salvage</li></ul>
                <table>
                  <tr><td>Gear 8</td><td>Mk 5 A/KT Stun Gun Salvage</td></tr>
                  <tr><td>Gear 9</td><td>Mk 12 ArmaTek Fusion Furnace Prototype Salvage</td></tr>
                  <tr><td>Gear 10</td><td>Mk 8 BioTech Implant Component</td></tr>
                </table>
                </body></html>
                """;

        GearParsingEngine.ParseResult result = engine.parse(html);

        assertThat(result.strategy()).isEqualTo("row-fallback-heuristics");
        assertThat(result.stats()).hasSize(3);
        assertThat(result.stats()).extracting(GearStat::gearLabel)
                .containsExactly("Gear 8", "Gear 9", "Gear 10");
    }
}
