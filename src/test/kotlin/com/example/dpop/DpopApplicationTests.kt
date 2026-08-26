package com.example.dpop

import io.kotest.core.spec.style.BehaviorSpec
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.modulith.core.ApplicationModules
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
class DpopApplicationTests : BehaviorSpec({

    given("the Spring application context") {
        then("it loads successfully") {
        }

        then("the modulith structure is valid") {
            val modules = ApplicationModules.of(DpopApplication::class.java)
            modules.verify()
        }
    }
})
