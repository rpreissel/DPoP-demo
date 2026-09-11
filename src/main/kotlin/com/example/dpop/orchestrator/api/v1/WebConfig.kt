package com.example.dpop.orchestrator.api.v1

import com.example.dpop.orchestrator.dpop.DpopBindingKeyResolver
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
     * `/` - the App-Kanal/Web-Kanal apps (docs/10-frontend.md #1) live at their own `/app/`/`/web/`
     * subpaths, which need the same forwarding spelled out explicitly.
     */
    override fun addViewControllers(registry: ViewControllerRegistry) {
        registry.addViewController("/app").setViewName("forward:/app/index.html")
        registry.addViewController("/app/").setViewName("forward:/app/index.html")
        registry.addViewController("/web").setViewName("forward:/web/index.html")
        registry.addViewController("/web/").setViewName("forward:/web/index.html")
    }
}
