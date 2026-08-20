package com.example.dpop

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.modulith.core.ApplicationModules
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
class DpopApplicationTests {

    @Test
    fun contextLoads() {
    }

    @Test
    fun modulithStructureIsValid() {
        val modules = ApplicationModules.of(DpopApplication::class.java)
        modules.verify()
    }
}