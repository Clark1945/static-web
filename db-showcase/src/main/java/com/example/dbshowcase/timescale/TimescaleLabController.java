package com.example.dbshowcase.timescale;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** TimescaleDB 實驗室的 API。 */
@RestController
@RequestMapping("/api/timescale/lab")
public class TimescaleLabController {

    public record SqlRequest(String sql) {
    }

    private final TimescaleLabService lab;

    public TimescaleLabController(TimescaleLabService lab) {
        this.lab = lab;
    }

    // ---------- chunk exclusion ----------

    @GetMapping("/steps")
    public List<TimescaleLabService.Step> steps() {
        return lab.steps();
    }

    @PostMapping("/explain")
    public TimescaleLabService.ExplainResult explain(@RequestBody SqlRequest request) {
        return lab.explain(request.sql());
    }

    // ---------- 壓縮 ----------

    @GetMapping("/compression")
    public TimescaleLabService.CompressionStatus compression() {
        return lab.compressionStatus();
    }

    @PostMapping("/compression/prepare")
    public TimescaleLabService.CompressionStatus prepare() {
        return lab.prepareCompression();
    }

    @PostMapping("/compression/compress")
    public TimescaleLabService.CompressionStatus compress(@RequestParam(defaultValue = "device") String segmentBy) {
        return lab.compress(segmentBy);
    }

    @PostMapping("/compression/decompress")
    public TimescaleLabService.CompressionStatus decompress() {
        return lab.decompress();
    }

    @PostMapping("/compression/benchmark")
    public List<TimescaleLabService.Benchmark> benchmark() {
        return lab.benchmark();
    }

    // ---------- 連續聚合 ----------

    @GetMapping("/aggregate")
    public TimescaleLabService.AggregateStatus aggregate() {
        return lab.aggregateStatus();
    }

    @PostMapping("/aggregate/prepare")
    public TimescaleLabService.AggregateStatus prepareAggregate() {
        return lab.prepareAggregate();
    }

    @PostMapping("/aggregate/realtime")
    public TimescaleLabService.AggregateStatus realtime(@RequestParam boolean enabled) {
        return lab.setRealtime(enabled);
    }

    @PostMapping("/aggregate/refresh")
    public TimescaleLabService.AggregateStatus refresh() {
        return lab.refreshAll();
    }

    @PostMapping("/aggregate/compare")
    public List<TimescaleLabService.Benchmark> compareAggregate() {
        return lab.compareAggregate();
    }
}
