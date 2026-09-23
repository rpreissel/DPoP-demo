package com.example.dpop.demo_seed

/**
 * Puts the demo accounts in place - on boot, and again right after the operator's demo reset
 * deleted every account (`AdminAccountsController.reset`). Only exists under the `keycloak`
 * profile; without it there are no demo accounts to restore.
 */
interface DemoAccountSeed {
    /** Creates what is missing, never touches an existing account; answers how many it created. */
    fun seed(): Int
}
