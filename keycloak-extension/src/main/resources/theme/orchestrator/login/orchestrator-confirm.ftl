<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "header">
        ${title!"Bestätigung erforderlich"}
    <#elseif section = "form">
        <form id="kc-orchestrator-confirm-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <div class="${properties.kcFormGroupClass!}">
                <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!} ${properties.kcButtonBlockClass!}"
                        type="submit" name="orchestrator_answer" value="accept">${confirmLabel!"Ja"}</button>
            </div>
            <div class="${properties.kcFormGroupClass!}">
                <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!} ${properties.kcButtonBlockClass!}"
                        type="submit" name="orchestrator_answer" value="decline">${cancelLabel!"Nein"}</button>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
