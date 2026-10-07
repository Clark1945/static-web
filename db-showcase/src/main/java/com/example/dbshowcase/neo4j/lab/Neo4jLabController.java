package com.example.dbshowcase.neo4j.lab;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.dbshowcase.neo4j.CypherShell;

/** Neo4j 實驗室的 API。 */
@RestController
@RequestMapping("/api/neo4j/lab")
public class Neo4jLabController {

    public record CypherRequest(String commands) {
    }

    private final Neo4jLabService lab;

    public Neo4jLabController(Neo4jLabService lab) {
        this.lab = lab;
    }

    @GetMapping("/steps")
    public List<Neo4jLabService.Step> steps() {
        return lab.steps();
    }

    @GetMapping("/indexes")
    public List<Neo4jLabService.IndexInfo> indexes() {
        return lab.indexes();
    }

    @PostMapping("/indexes")
    public List<Neo4jLabService.IndexInfo> ddl(@RequestBody CypherRequest request) {
        return lab.ddl(request.commands());
    }

    @PostMapping("/indexes/reset")
    public List<Neo4jLabService.IndexInfo> dropIndexes() {
        return lab.dropIndexes();
    }

    @PostMapping("/profile")
    public CypherShell.Outcome profile(@RequestBody CypherRequest request) {
        return lab.profile(request.commands());
    }

    @PostMapping("/compare/recommend")
    public Neo4jLabService.Comparison recommend(@RequestParam(defaultValue = "540") int productId) {
        return lab.recommend(productId);
    }

    @PostMapping("/compare/reach")
    public List<Neo4jLabService.Comparison> reach(@RequestParam(defaultValue = "4242") int customerId,
                                                  @RequestParam(defaultValue = "5") int depth) {
        return lab.reach(customerId, depth);
    }

    @PostMapping("/shortest-path")
    public Neo4jLabService.ShortestPath shortestPath(@RequestParam(defaultValue = "4242") int from,
                                            @RequestParam(defaultValue = "1") int to) {
        return lab.shortestPath(from, to);
    }
}
