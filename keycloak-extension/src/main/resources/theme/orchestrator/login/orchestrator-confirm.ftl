<#import "template.ftl" as layout>
<#import "page-notes.ftl" as pageNotes>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "header">
        ${title!t.of("Bestätigung erforderlich")}
    <#elseif section = "form">
        <@pageNotes.notes/>
        <form id="kc-orchestrator-confirm-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <div class="orchestrator-actions">
                <button class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!}"
                        type="submit" name="orchestrator_answer" value="accept">${confirmLabel!t.of("Ja")}</button>
                <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!}"
                        type="submit" name="orchestrator_answer" value="decline">${cancelLabel!t.of("Nein")}</button>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
