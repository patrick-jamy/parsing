package com.example.gearparser.service;

import com.example.gearparser.model.GearStat;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GearParsingEngineTest {

    private final GearParsingEngine engine = new GearParsingEngine();

    @Test
    public void shouldParseGearSectionsAndDetectColors() throws Exception {
        String html = Files.readString(Path.of("src/test/resources/sample-gear-page.html"));

        GearParsingEngine.ParseResult result = engine.parse(html);
        List<GearStat> stats = result.stats();

        assertEquals("multi-source-merged", result.strategy());
        assertEquals(2, stats.size());

        GearStat gear8 = stats.get(0);
        assertEquals("Gear 8", gear8.gearLabel());
        assertEquals(3, gear8.totalItems());
        assertEquals(Integer.valueOf(1), gear8.colorCounts().get("bleu"));
        assertEquals(Integer.valueOf(1), gear8.colorCounts().get("orange"));

        GearStat gear9 = stats.get(1);
        assertEquals("Gear 9", gear9.gearLabel());
        assertEquals(2, gear9.totalItems());
    }

    @Test
    public void shouldParseMixedSourcesWithoutDroppingGears() {
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

        assertEquals("multi-source-merged", result.strategy());
        assertEquals(3, result.stats().size());
        assertEquals("Gear 8", result.stats().get(0).gearLabel());
        assertEquals("Gear 9", result.stats().get(1).gearLabel());
        assertEquals("Gear 10", result.stats().get(2).gearLabel());
    }

    @Test
    public void shouldParseAllGearsFromEmbeddedDataObjects() {
        String html = """
                <html><body><script>
                const gear = [
                  {gear: 1, name: "Mk 1 Sienar Holo Projector Salvage"},
                  {gear: 2, name: "Mk 2 CEC Fusion Furnace Salvage"},
                  {gear: 3, name: "Mk 3 Carbanti Sensor Array Salvage"},
                  {gear: 4, name: "Mk 4 Chiewab Hypo Syringe Salvage"},
                  {gear: 5, name: "Mk 5 A/KT Stun Gun Salvage"},
                  {gear: 6, name: "Mk 6 Athakam Medpac Salvage"},
                  {gear: 7, name: "Mk 7 Kyrotech Shock Prod Prototype Salvage"},
                  {gear: 8, name: "Mk 8 BioTech Implant Component"},
                  {gear: 9, name: "Mk 9 Neuro-Saav Electrobinoculars Component"},
                  {gear: 10, name: "Mk 10 TaggeCo Holo Lens Salvage"},
                  {gear: 11, name: "Mk 11 BlasTech Weapon Mod Prototype"},
                  {gear: 12, name: "Mk 12 ArmaTek Fusion Furnace Prototype Salvage"}
                ];
                </script></body></html>
                """;

        GearParsingEngine.ParseResult result = engine.parse(html);

        assertEquals("multi-source-merged", result.strategy());
        assertEquals(12, result.stats().size());
        for (int i = 0; i < 12; i++) {
            assertEquals("Gear " + (i + 1), result.stats().get(i).gearLabel());
        }
    }

    @Test
    public void shouldParseNestedJsonWithoutRepeatingGearOnEveryItem() {
        String html = """
                <html><body><script>
                window.__NEXT_DATA__ = {
                  "props": {
                    "pageProps": {
                      "tiers": [
                        {"gearLevel": 1, "ingredients": [{"name": "Mk 1 Sienar Holo Projector Salvage"}]},
                        {"gearLevel": 2, "ingredients": [{"name": "Mk 2 CEC Fusion Furnace Salvage"}]},
                        {"gearLevel": 3, "ingredients": [{"name": "Mk 3 Carbanti Sensor Array Salvage"}]},
                        {"gearLevel": 4, "ingredients": [{"name": "Mk 4 Chiewab Hypo Syringe Salvage"}]},
                        {"gearLevel": 5, "ingredients": [{"name": "Mk 5 A/KT Stun Gun Salvage"}]},
                        {"gearLevel": 6, "ingredients": [{"name": "Mk 6 Athakam Medpac Salvage"}]},
                        {"gearLevel": 7, "ingredients": [{"name": "Mk 7 Kyrotech Shock Prod Prototype Salvage"}]},
                        {"gearLevel": 8, "ingredients": [{"name": "Mk 8 BioTech Implant Component"}]},
                        {"gearLevel": 9, "ingredients": [{"name": "Mk 9 Neuro-Saav Electrobinoculars Component"}]},
                        {"gearLevel": 10, "ingredients": [{"name": "Mk 10 TaggeCo Holo Lens Salvage"}]},
                        {"gearLevel": 11, "ingredients": [{"name": "Mk 11 BlasTech Weapon Mod Prototype"}]},
                        {"gearLevel": 12, "ingredients": [{"name": "Mk 12 ArmaTek Fusion Furnace Prototype Salvage"}]}
                      ]
                    }
                  }
                };
                </script></body></html>
                """;

        GearParsingEngine.ParseResult result = engine.parse(html);

        assertEquals("multi-source-merged", result.strategy());
        assertEquals(12, result.stats().size());
        for (int i = 0; i < 12; i++) {
            assertEquals("Gear " + (i + 1), result.stats().get(i).gearLabel());
        }
    }

    @Test
    public void shouldNotStopAfterTwoScriptSegments() {
        String filler = "x".repeat(1600);
        String html = "<html><body><script>"
                + "gear 8 " + filler + " Mk 8 BioTech Implant Component "
                + " gear 9 " + filler + " Mk 9 Neuro-Saav Electrobinoculars Component "
                + " gear 10 " + filler + " Mk 10 TaggeCo Holo Lens Salvage "
                + "</script></body></html>";

        GearParsingEngine.ParseResult result = engine.parse(html);

        List<String> labels = result.stats().stream().map(GearStat::gearLabel).toList();
        assertTrue(labels.contains("Gear 8"));
        assertTrue(labels.contains("Gear 9"));
        assertTrue(labels.contains("Gear 10"));
    }
}
