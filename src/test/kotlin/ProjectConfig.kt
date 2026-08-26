import io.kotest.core.config.AbstractProjectConfig
import io.kotest.extensions.spring.SpringExtension

/**
 * Auto-discovered by Kotest via classpath scanning (no manual registration needed). Registers
 * SpringExtension globally so any spec constructor-injects Spring beans the same way a JUnit5
 * @SpringBootTest class does - required for that injection to work at all, since Kotest must
 * know to delegate spec instantiation to Spring's TestContextManager before it can resolve
 * constructor parameters.
 */
class ProjectConfig : AbstractProjectConfig() {
    override fun extensions() = listOf(SpringExtension)
}
