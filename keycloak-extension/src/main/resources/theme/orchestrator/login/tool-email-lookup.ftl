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
            <#if step == "codeInput">
                <div class="${properties.kcFormGroupClass!}">
                    <label for="code" class="${properties.kcLabelClass!}">Bestätigungscode</label>
                    <input type="text" id="code" name="code" class="${properties.kcInputClass!}" autocomplete="off"/>
                    <#if demoTan??>
                        <span class="orchestrator-hint">Demo-Code: ${demoTan}</span>
                    </#if>
                </div>
            <#else>
                <@demoPerson.personPicker personsJson=demoPersonsJson! fieldMapJson='{"email":"email"}' />
                <div class="${properties.kcFormGroupClass!}">
                    <label for="email" class="${properties.kcLabelClass!}">E-Mail-Adresse</label>
                    <input type="email" id="email" name="email" class="${properties.kcInputClass!}" autocomplete="off"/>
                    <#if demoEmail??>
                        <span class="orchestrator-hint">Demo-E-Mail: ${demoEmail}</span>
                    </#if>
                </div>
            </#if>
            <div class="orchestrator-actions">
                <button class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!}" type="submit">Weiter</button>
                <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!}"
                        type="submit" name="orchestrator_abandon" value="true">Zurück</button>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
