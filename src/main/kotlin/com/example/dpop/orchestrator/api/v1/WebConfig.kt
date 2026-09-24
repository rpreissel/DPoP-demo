package com.example.dpop.orchestrator.api.v1

import com.example.dpop.orchestrator.api.v1.DpopBindingKeyResolver
import org.springframework.context.annotation.Configuration
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfig(private val dpopBindingKeyResolver: DpopBindingKeyResolver) : WebMvcConfigurer {
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(dpopBindingKeyResolver)
    }

    /**
     * Spring Boot's default static-resource welcome page only resolves `index.html` for the root
     * `/` - the other apps (docs/10-frontend.md #0) live at their own subpaths, which need the
     * same forwarding spelled out explicitly.
     */
    override fun addViewControllers(registry: ViewControllerRegistry) {
        for (app in PAGE_APPS) {
            registry.addViewController("/$app").setViewName("forward:/$app/index.html")
            registry.addViewController("/$app/").setViewName("forward:/$app/index.html")
        }
    }

    private companion object {
        /** One entry per Vite page (frontend/vite.config.ts `input`) besides the root welcome page. */
        val PAGE_APPS = listOf("app", "web", "admin", "personenverzeichnis", "nect")
    }
}
