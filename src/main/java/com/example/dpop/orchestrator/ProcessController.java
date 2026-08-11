package com.example.dpop.orchestrator;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orchestrator")
public class ProcessController {

    private final Orchestrator orchestrator;

    public ProcessController(Orchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @GetMapping("/process")
    public String process() {
        return orchestrator.runProcess();
    }
}
