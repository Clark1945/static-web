package com.example.dbshowcase.mongo.lab;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** MongoDB 實驗室的 API。 */
@RestController
@RequestMapping("/api/mongo/lab")
public class MongoLabController {

    public record CommandRequest(String commands) {
    }

    private final MongoLabService lab;

    public MongoLabController(MongoLabService lab) {
        this.lab = lab;
    }

    // ---------- 索引與 explain ----------

    @GetMapping("/steps")
    public List<MongoLabService.Step> steps() {
        return lab.steps();
    }

    @GetMapping("/indexes")
    public List<MongoLabService.IndexInfo> indexes() {
        return lab.indexes();
    }

    @PostMapping("/indexes")
    public List<MongoLabService.IndexInfo> ddl(@RequestBody CommandRequest request) {
        return lab.ddl(request.commands());
    }

    @PostMapping("/indexes/reset")
    public List<MongoLabService.IndexInfo> resetIndexes() {
        return lab.resetIndexes();
    }

    @PostMapping("/explain")
    public MongoLabService.ExplainSummary explain(@RequestBody CommandRequest request) {
        return lab.explain(request.commands());
    }

    // ---------- 內嵌 vs 參照 ----------

    @GetMapping("/embed")
    public MongoLabService.EmbedStatus embedStatus() {
        return lab.embedStatus();
    }

    @PostMapping("/embed/prepare")
    public MongoLabService.EmbedStatus prepare() {
        return lab.prepareEmbed();
    }

    @PostMapping("/embed/index")
    public MongoLabService.EmbedStatus indexLines(@RequestParam boolean create) {
        return lab.indexLines(create);
    }

    @PostMapping("/embed/compare")
    public List<MongoLabService.Comparison> compare(@RequestParam int customerId) {
        return lab.compare(customerId);
    }

    // ---------- 聚合管線逐步看 ----------

    @PostMapping("/pipeline")
    public List<MongoLabService.StageResult> pipeline(@RequestBody CommandRequest request) {
        return lab.pipeline(request.commands());
    }
}
