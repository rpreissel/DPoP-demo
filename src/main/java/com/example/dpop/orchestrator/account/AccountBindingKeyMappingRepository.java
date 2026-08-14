package com.example.dpop.orchestrator.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AccountBindingKeyMappingRepository extends JpaRepository<AccountBindingKeyMapping, String> {

    Optional<AccountBindingKeyMapping> findByBindingKeyRef(String bindingKeyRef);
}
