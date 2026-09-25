<#import "template.ftl" as layout>
<#import "made-with.ftl" as madeWith>
<#import "demo-person-picker.ftl" as demoPerson>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "header">
        ${title}
    <#elseif section = "form">
        <@madeWith.note/>
        <#if hint??>
            <p class="orchestrator-subtitle">${hint}</p>
        </#if>
        <form id="kc-orchestrator-tool-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <p class="orchestrator-hint">
                ${t.of("Ihre Identität ist bereits nachgewiesen. Die Versichertennummer - oder ohne sie die Partnernummer - muss zu dieser Person gehören.")}
            </p>
            <@demoPerson.personPicker personsJson=demoPersonsJson! fieldMapJson='{"kvnr":"kvnr","partnernr":"personId"}' />
            <div class="${properties.kcFormGroupClass!}">
                <label for="kvnr" class="${properties.kcLabelClass!}">${t.of("Versichertennummer")}</label>
                <input type="text" id="kvnr" name="kvnr" class="${properties.kcInputClass!}"/>
            </div>
            <#-- The KVNR comes first; the Partnernummer only counts without one (ADR-34). -->
            <details class="${properties.kcFormGroupClass!}">
                <summary>${t.of("Ich habe keine Versichertennummer")}</summary>
                <label for="partnernr" class="${properties.kcLabelClass!}">${t.of("Partnernummer")}</label>
                <input type="text" id="partnernr" name="partnernr" class="${properties.kcInputClass!}" placeholder="P000000000"/>
            </details>
            <div class="orchestrator-actions">
                <button class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!}" type="submit">${t.of("Zuordnen")}</button>
                <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!}"
                        type="submit" name="orchestrator_abandon" value="true">${t.of("Zurück")}</button>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
