package com.example.dbshowcase.postgres.lab;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 索引實驗室的 API。查詢本身（EXPLAIN ANALYZE）走一般的 /api/postgres/sql。 */
@RestController
@RequestMapping("/api/postgres/lab")
public class IndexLabController {

    public record DdlRequest(String sql) {
    }

    private final IndexLabService lab;

    public IndexLabController(IndexLabService lab) {
        this.lab = lab;
    }

    @GetMapping("/steps")
    public List<LabStep> steps() {
        return lab.steps();
    }

    @GetMapping("/info")
    public IndexLabService.LabInfo info() {
        return lab.info();
    }

    @PostMapping("/ddl")
    public IndexLabService.DdlResult ddl(@RequestBody DdlRequest request) {
        return lab.execute(request.sql());
    }

    @PostMapping("/reset")
    public IndexLabService.DdlResult reset() {
        return lab.reset();
    }
}
