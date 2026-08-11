package com.example.dpop.auth_sms;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuthSmsSetupRepository extends JpaRepository<AuthSmsSetup, Long> {
}
