package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.domain.ToolCatalog
import com.example.dpop.orchestrator.domain.journey.IntentStrategy
import com.example.dpop.orchestrator.domain.policy.AuthPolicy
import com.example.dpop.orchestrator.domain.policy.DefaultAuthPolicy
import com.example.dpop.orchestrator.domain.journey.strategy.ConfirmPeerLoginStrategy
import com.example.dpop.orchestrator.domain.journey.strategy.DeleteAccountStrategy
import com.example.dpop.orchestrator.domain.journey.strategy.FastAccessStrategy
import com.example.dpop.orchestrator.domain.journey.strategy.KcSelectMethodStrategy
import com.example.dpop.orchestrator.domain.journey.strategy.LogoutStrategy
import com.example.dpop.orchestrator.domain.journey.strategy.LookupLoginStrategy
import com.example.dpop.orchestrator.domain.journey.strategy.ManageAuthMethodsStrategy
import com.example.dpop.orchestrator.domain.journey.strategy.ReIdentifyStrategy
import com.example.dpop.orchestrator.domain.journey.strategy.RegisterDispatchStrategy
import com.example.dpop.orchestrator.domain.journey.strategy.StepUpStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires the domain's rule objects into Spring, so the domain itself carries no framework
 * annotation (docs/adr/ADR-040-fachkern-im-paket-domain.md). Also the one place that lists which
 * intent strategies exist - `JourneyService` checks at startup that every `AuthIntent` has one.
 *
 * `RegisterStrategy` and `RegisterEnrollFirstStrategy` are deliberately not listed: REGISTER is
 * served by `RegisterDispatchStrategy`, which holds both (only one strategy per intent).
 */
@Configuration(proxyBeanMethods = false)
class DomainBeans {

    @Bean
    fun authPolicy(catalog: ToolCatalog): AuthPolicy = DefaultAuthPolicy(catalog)

    @Bean
    fun confirmPeerLoginStrategy(): IntentStrategy<*> = ConfirmPeerLoginStrategy()

    @Bean
    fun deleteAccountStrategy(): IntentStrategy<*> = DeleteAccountStrategy()

    @Bean
    fun fastAccessStrategy(): IntentStrategy<*> = FastAccessStrategy()

    @Bean
    fun kcSelectMethodStrategy(): IntentStrategy<*> = KcSelectMethodStrategy()

    @Bean
    fun logoutStrategy(): IntentStrategy<*> = LogoutStrategy()

    @Bean
    fun lookupLoginStrategy(): IntentStrategy<*> = LookupLoginStrategy()

    @Bean
    fun manageAuthMethodsStrategy(): IntentStrategy<*> = ManageAuthMethodsStrategy()

    @Bean
    fun reIdentifyStrategy(): IntentStrategy<*> = ReIdentifyStrategy()

    @Bean
    fun registerDispatchStrategy(): IntentStrategy<*> = RegisterDispatchStrategy()

    @Bean
    fun stepUpStrategy(): IntentStrategy<*> = StepUpStrategy()
}
