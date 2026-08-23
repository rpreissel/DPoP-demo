package com.example.dpop

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class DpopApplication

fun main(args: Array<String>) {
    runApplication<DpopApplication>(*args)
}
