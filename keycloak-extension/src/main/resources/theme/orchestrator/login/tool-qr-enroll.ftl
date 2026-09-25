<#import "template.ftl" as layout>
<#import "page-notes.ftl" as pageNotes>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "header">
        ${t.of("Web-Login per QR-Code erlauben")}
    <#elseif section = "form">
        <@pageNotes.notes/>
        <p class="orchestrator-subtitle">
            ${t.of("Erlaubt, dass Sie künftig eine Anmeldung auf der Website mit Ihrer angemeldeten App per QR-Code bestätigen. Ein zusätzliches Passwort brauchen Sie dafür nicht.")}
        </p>

        <form id="kc-orchestrator-tool-form" action="${url.loginAction}" method="post">
            <div class="orchestrator-actions">
                <button class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!}"
                        type="submit">${t.of("Aktivieren")}</button>
                <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!}"
                        type="submit" name="orchestrator_abandon" value="true">${t.of("Abbrechen")}</button>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
