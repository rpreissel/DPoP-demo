package com.example.dpop.orchestrator

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/orchestrator")
class ProcessController(private val orchestrator: Orchestrator) {

    @GetMapping("/process")
    fun process(): String = orchestrator.runProcess()
}
