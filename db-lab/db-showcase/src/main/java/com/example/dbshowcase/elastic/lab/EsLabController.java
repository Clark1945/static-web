package com.example.dbshowcase.elastic.lab;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.dbshowcase.elastic.EsShell;

/** Elasticsearch 實驗室的 API：/api/elastic/lab/… */
@RestController
@RequestMapping("/api/elastic/lab")
public class EsLabController {

    public record AnalyzeRequest(String text, List<String> analyzers) {
    }

    public record TextRequest(String text) {
    }

    public record ScriptRequest(String commands) {
    }

    private final EsLabService lab;

    public EsLabController(EsLabService lab) {
        this.lab = lab;
    }

    @PostMapping("/analyze")
    public List<EsLabService.Tokens> analyze(@RequestBody AnalyzeRequest request) {
        return lab.analyze(request.text(), request.analyzers() == null ? List.of("standard", "cjk") : request.analyzers());
    }

    @PostMapping("/compare")
    public List<EsLabService.Compare> compare(@RequestBody TextRequest request) {
        return lab.compare(request.text());
    }

    @GetMapping("/relevance")
    public List<EsLabService.Step> relevance() {
        return lab.relevanceSteps();
    }

    @PostMapping("/relevance/run")
    public EsShell.RunResult runRelevance(@RequestBody ScriptRequest request) {
        return lab.runReadOnly(request.commands());
    }

    @PostMapping("/relevance/explain")
    public List<EsLabService.Explained> explain(@RequestBody ScriptRequest request) {
        return lab.explain(request.commands());
    }

    @GetMapping("/behavior")
    public List<EsLabService.Step> behavior() {
        return lab.behaviorSteps();
    }

    @PostMapping("/behavior/run")
    public EsShell.RunResult runBehavior(@RequestBody ScriptRequest request) {
        return lab.runScratch(request.commands());
    }
}
