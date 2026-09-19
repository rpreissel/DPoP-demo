<#import "template.ftl" as layout>
<#import "demo-person-picker.ftl" as demoPerson>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "header">
        ${title}
    <#elseif section = "form">
        <#if hint??>
            <p class="orchestrator-subtitle">${hint}</p>
        </#if>
        <form id="kc-orchestrator-tool-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <p class="orchestrator-hint">
                Ihre Identität ist bereits nachgewiesen. Die Versichertennummer muss zu dieser Person gehören.
            </p>
            <@demoPerson.personPicker personsJson=demoPersonsJson! fieldMapJson='{"kvnr":"kvnr"}' />
            <div class="${properties.kcFormGroupClass!}">
                <label for="kvnr" class="${properties.kcLabelClass!}">Krankenversichertennummer</label>
                <input type="text" id="kvnr" name="kvnr" class="${properties.kcInputClass!}" value="A123456789"/>
            </div>
            <div class="orchestrator-actions">
                <button class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!}" type="submit">Zuordnen</button>
                <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!}"
                        type="submit" name="orchestrator_abandon" value="true">Zurück</button>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
