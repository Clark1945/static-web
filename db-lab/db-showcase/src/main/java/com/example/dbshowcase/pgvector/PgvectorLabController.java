package com.example.dbshowcase.pgvector;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** pgvector 實驗室的 API：/api/pgvector/lab/… */
@RestController
@RequestMapping("/api/pgvector/lab")
public class PgvectorLabController {

    public record SearchRequest(String text) {
    }

    public record IndexRequest(String method, Integer efSearch, Integer probes) {
    }

    public record IvfRequest(int lists) {
    }

    public record FilterRequest(String filter, String mode, Integer efSearch) {
    }

    public record QuantRequest(String method, Integer efSearch) {
    }

    private final PgvectorLabService lab;

    public PgvectorLabController(PgvectorLabService lab) {
        this.lab = lab;
    }

    @PostMapping("/search")
    public PgvectorLabService.SearchResult search(@RequestBody SearchRequest request) {
        return lab.search(request.text());
    }

    @GetMapping("/index")
    public PgvectorLabService.IndexStatus indexStatus() {
        return lab.indexStatus();
    }

    @PostMapping("/index/ivfflat")
    public PgvectorLabService.IndexStatus buildIvf(@RequestBody IvfRequest request) {
        return lab.buildIvf(request.lists());
    }

    @PostMapping("/index/run")
    public PgvectorLabService.Bench runIndex(@RequestBody IndexRequest request) {
        return lab.runIndex(request.method(), or(request.efSearch(), 40), or(request.probes(), 1));
    }

    @PostMapping("/filter/run")
    public PgvectorLabService.Bench runFilter(@RequestBody FilterRequest request) {
        return lab.runFilter(request.filter(), request.mode(), or(request.efSearch(), 40));
    }

    @GetMapping("/quant")
    public PgvectorLabService.QuantStatus quantStatus() {
        return lab.quantStatus();
    }

    @PostMapping("/quant/prepare")
    public PgvectorLabService.QuantStatus prepareQuant() {
        return lab.prepareQuant();
    }

    @PostMapping("/quant/run")
    public PgvectorLabService.Bench runQuant(@RequestBody QuantRequest request) {
        return lab.runQuant(request.method(), or(request.efSearch(), 40));
    }

    private static int or(Integer v, int fallback) {
        return v == null ? fallback : v;
    }
}
