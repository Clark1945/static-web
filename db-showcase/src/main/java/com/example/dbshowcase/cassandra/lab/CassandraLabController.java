package com.example.dbshowcase.cassandra.lab;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.dbshowcase.cassandra.CqlShell;

/** Cassandra 實驗室的 API。 */
@RestController
@RequestMapping("/api/cassandra/lab")
public class CassandraLabController {

    public record CqlRequest(String commands) {
    }

    private final CassandraLabService lab;

    public CassandraLabController(CassandraLabService lab) {
        this.lab = lab;
    }

    // ---------- 查詢與表設計 ----------

    @GetMapping("/steps")
    public List<CassandraLabService.Step> steps() {
        return lab.steps();
    }

    @PostMapping("/trace")
    public CqlShell.Result trace(@RequestBody CqlRequest request) {
        return lab.trace(request.commands());
    }

    @GetMapping("/indexes")
    public List<CassandraLabService.IndexInfo> indexes() {
        return lab.indexes();
    }

    @PostMapping("/indexes")
    public List<CassandraLabService.IndexInfo> ddl(@RequestBody CqlRequest request) {
        return lab.ddl(request.commands());
    }

    @PostMapping("/indexes/reset")
    public List<CassandraLabService.IndexInfo> dropIndexes() {
        return lab.dropIndexes();
    }

    // ---------- 墓碑 ----------

    @GetMapping("/tombstones")
    public CassandraLabService.TombstoneStatus tombstones() {
        return lab.tombstoneStatus();
    }

    @PostMapping("/tombstones/prepare")
    public CassandraLabService.TombstoneStatus prepare(@RequestParam(defaultValue = "10000") int messages) {
        return lab.prepareTombstones(messages);
    }

    @PostMapping("/tombstones/overwhelm")
    public CassandraLabService.TombstoneStatus overwhelm() {
        return lab.overwhelm();
    }

    @PostMapping("/tombstones/read")
    public List<CassandraLabService.TombstoneRead> read() {
        return lab.readQueues();
    }

    // ---------- 一致性與輕量交易 ----------

    @PostMapping("/consistency")
    public List<CassandraLabService.ConsistencyResult> consistency() {
        return lab.consistency();
    }

    @PostMapping("/oversell")
    public CassandraLabService.OversellResult oversell(@RequestParam String mode,
                                                       @RequestParam(defaultValue = "50") int buyers,
                                                       @RequestParam(defaultValue = "10") int stock) {
        return lab.oversell(mode, buyers, stock);
    }

    @PostMapping("/lwt-cost")
    public List<CqlShell.Result> lwtCost() {
        return lab.lwtCost();
    }
}
