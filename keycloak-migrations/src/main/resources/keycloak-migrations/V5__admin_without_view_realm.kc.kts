// ===================== V5__admin_without_view_realm =====================

// orchestrator-admin braucht view-realm nicht mehr (Review 2026-09-26, A-1): V1 vergab die Rolle fuer
// KeycloakAdminClient.passwordStorageComponentId(), das es nicht mehr gibt; das Login-Theme schreibt
// orchestrator-migration. Was bleibt, deckt manage-users: eine Sitzung beenden, ein geloeschtes Konto
// aufraeumen (AccountRemoval prueft users().requireManage()).

step("service-account view-realm Rolle entziehen") {
    up {
        val realmMgmtId = clientDbId("realm-management")
        val role = clients().get(realmMgmtId).roles().get("view-realm").toRepresentation()
        val serviceAccount = clients().get(clientDbId(setup.adminApiClientId)).serviceAccountUser.id
        users().get(serviceAccount).roles().clientLevel(realmMgmtId).remove(listOf(role))
    }
    down {
        val realmMgmtId = clientDbId("realm-management")
        val role = clients().get(realmMgmtId).roles().get("view-realm").toRepresentation()
        val serviceAccount = clients().get(clientDbId(setup.adminApiClientId)).serviceAccountUser.id
        users().get(serviceAccount).roles().clientLevel(realmMgmtId).add(listOf(role))
    }
}
