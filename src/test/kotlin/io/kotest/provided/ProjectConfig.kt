package io.kotest.provided

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.extensions.spring.SpringExtension

/**
 * Registers SpringExtension globally so any spec constructor-injects Spring beans the same way a
 * JUnit5 @SpringBootTest class does - required for that injection to work at all, since Kotest
 * must know to delegate spec instantiation to Spring's TestContextManager before it can resolve
 * constructor parameters.
 *
 * The package is not a choice: Kotest 6 no longer scans the classpath for project configs and
 * loads exactly `io.kotest.provided.ProjectConfig`. Anywhere else, this class would silently not
 * run - and every Spring-injected spec would fail with a constructor error instead.
 */
class ProjectConfig : AbstractProjectConfig() {
    override val extensions = listOf(SpringExtension())
}
