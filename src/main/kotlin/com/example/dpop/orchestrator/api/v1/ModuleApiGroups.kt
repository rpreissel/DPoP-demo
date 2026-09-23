package com.example.dpop.orchestrator.api.v1

import org.springdoc.core.models.GroupedOpenApi
import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar
import org.springframework.core.type.AnnotationMetadata
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.web.bind.annotation.RestController

/**
 * One OpenAPI group per module that has endpoints, plus one combined group.
 *
 * The API contract is checked in as YAML (`api/`, see `OpenApiSnapshotTest`). In a single
 * 4200-line file it is hard to review: a change to one SMS endpoint has to be found somewhere in
 * the contract of all fourteen modules. Per module it is a file of a few hundred lines, and a diff
 * already names the module it belongs to.
 *
 * The groups are derived from the controllers that actually exist, not from a list maintained
 * here. A new method module appears because it has a `@RestController`, which is the same rule the
 * tool catalog, the retention sweep and the Flyway locations follow - a list would be one more
 * place to forget it.
 *
 * [COMBINED_GROUP] stays the input for the code generator (`generateFrontendApiTypes`): the
 * response envelope is shared by every module, so generating from the per-module files would
 * produce the same types again and again.
 */
@Configuration
@Import(Registrar::class)
class ModuleApiGroups

/**
 * Registers the groups as bean DEFINITIONS, because the set of modules is only known at runtime.
 *
 * Two things rule out the simpler routes. A `@Bean` returning `List<GroupedOpenApi>` is one bean of
 * type `List`, and springdoc looks for beans of type `GroupedOpenApi` - it would find none. And a
 * `BeanDefinitionRegistryPostProcessor` runs too late: springdoc's group endpoints hang off a
 * `@ConditionalOnBean(GroupedOpenApi)`, which is evaluated while configuration classes are parsed,
 * so the groups have to exist by then. An `ImportBeanDefinitionRegistrar` runs exactly there.
 */
class Registrar : ImportBeanDefinitionRegistrar {

    override fun registerBeanDefinitions(metadata: AnnotationMetadata, registry: BeanDefinitionRegistry) {
        register(registry, COMBINED_GROUP, ROOT_PACKAGE)
        modulesWithEndpoints().forEach { module ->
            register(registry, module, "$ROOT_PACKAGE.$module")
        }
    }

    private fun register(registry: BeanDefinitionRegistry, group: String, packageToScan: String) {
        val definition = BeanDefinitionBuilder
            .genericBeanDefinition(GroupedOpenApi::class.java) {
                GroupedOpenApi.builder().group(group).packagesToScan(packageToScan).build()
            }
            .beanDefinition
        registry.registerBeanDefinition("openApiGroup-$group", definition)
    }

    /**
     * Every `com.example.dpop.<module>` that contains at least one `@RestController`. Scanned
     * rather than asked of Spring Modulith, because `spring-modulith-core` is a test-scope
     * dependency here - and a controller is the more direct answer to "does this module serve an
     * API" anyway.
     */
    private fun modulesWithEndpoints(): List<String> {
        val scanner = ClassPathScanningCandidateComponentProvider(false).apply {
            addIncludeFilter(AnnotationTypeFilter(RestController::class.java))
        }
        return scanner.findCandidateComponents(ROOT_PACKAGE)
            .mapNotNull { it.beanClassName }
            .mapNotNull { it.removePrefix("$ROOT_PACKAGE.").substringBefore('.').takeIf { name -> name.isNotEmpty() } }
            .distinct()
            .sorted()
    }

    private companion object {
        private const val ROOT_PACKAGE = "com.example.dpop"
    }
}

/** Shared by the registrar and by `OpenApiSnapshotTest`, which names the combined group's file. */
const val COMBINED_GROUP = "alle"
