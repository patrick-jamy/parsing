package com.example.gearparser.controller;

import com.example.gearparser.model.ParseResponse;
import com.example.gearparser.service.GearStatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stats")
public class GearStatsController {

    private final GearStatsService service;

    public GearStatsController(GearStatsService service) {
        this.service = service;
    }

    @GetMapping
    public ParseResponse stats() {
        return service.getLatest();
    }

    @PostMapping("/reload")
    public ParseResponse reload() {
        return service.reload();
    }
}
