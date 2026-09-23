package com.example.dpop

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
// Fuer tool_api.ToolSessionRetentionProperties - die eine Stelle, an der die Aufbewahrungs-
// frist der Tool-Session-Daten steht (docs/07-betrieb.md #3).
@ConfigurationPropertiesScan
@EnableScheduling
class DpopApplication

fun main(args: Array<String>) {
    runApplication<DpopApplication>(*args)
}
