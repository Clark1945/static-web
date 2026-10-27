package com.example.dbshowcase.influx.lab;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.dbshowcase.influx.InfluxShell;

/** InfluxDB 實驗室的 API：/api/influx/lab/… */
@RestController
@RequestMapping("/api/influx/lab")
public class InfluxLabController {

    public record CardinalityRequest(Integer users) {
    }

    public record RunRequest(String lang, String code) {
    }

    private final InfluxLabService lab;

    public InfluxLabController(InfluxLabService lab) {
        this.lab = lab;
    }

    @PostMapping("/cardinality")
    public InfluxLabService.Cardinality cardinality(@RequestBody CardinalityRequest request) {
        return lab.cardinality(request.users() == null ? 1000 : request.users());
    }

    @GetMapping("/compare")
    public List<InfluxLabService.Step> steps() {
        return lab.steps();
    }

    @PostMapping("/compare/run")
    public InfluxShell.RunResult run(@RequestBody RunRequest request) {
        return lab.runReadOnly(request.lang(), request.code());
    }

    @PostMapping("/downsample")
    public InfluxLabService.Downsample downsample() {
        return lab.downsample();
    }
}
