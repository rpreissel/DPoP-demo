<#import "template.ftl" as layout>
<#import "made-with.ftl" as madeWith>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "header">
        ${title!t.of("Bestätigung erforderlich")}
    <#elseif section = "form">
        <@madeWith.note/>
        <form id="kc-orchestrator-confirm-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <div class="${properties.kcFormGroupClass!}">
                <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!} ${properties.kcButtonBlockClass!}"
                        type="submit" name="orchestrator_answer" value="accept">${confirmLabel!t.of("Ja")}</button>
            </div>
            <div class="${properties.kcFormGroupClass!}">
                <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!} ${properties.kcButtonBlockClass!}"
                        type="submit" name="orchestrator_answer" value="decline">${cancelLabel!t.of("Nein")}</button>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
