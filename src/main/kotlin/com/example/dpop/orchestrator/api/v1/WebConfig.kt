package com.example.dpop.orchestrator.api.v1

import com.example.dpop.orchestrator.dpop.DpopBindingKeyResolver
import org.springframework.context.annotation.Configuration
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfig(private val dpopBindingKeyResolver: DpopBindingKeyResolver) : WebMvcConfigurer {
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(dpopBindingKeyResolver)
    }
}
