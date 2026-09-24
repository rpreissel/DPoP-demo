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
            <@demoPerson.personPicker personsJson=demoPersonsJson! fieldMapJson='{"email":"email"}' />
            <div class="${properties.kcFormGroupClass!}">
                <label for="email" class="${properties.kcLabelClass!}">${t.of("E-Mail-Adresse")}</label>
                <input type="email" id="email" name="email" class="${properties.kcInputClass!}" autocomplete="off"/>
                <#if demoEmail??>
                    <span class="orchestrator-hint">${t.of("Demo-E-Mail: {wert}", {"wert": demoEmail})}</span>
                </#if>
            </div>
            <div class="${properties.kcFormGroupClass!}">
                <label for="password" class="${properties.kcLabelClass!}">${t.of("Passwort")}</label>
                <input type="password" id="password" name="password" class="${properties.kcInputClass!}" autocomplete="off"/>
                <#if demoPassword??>
                    <span class="orchestrator-hint">${t.of("Demo-Passwort: {wert}", {"wert": demoPassword})}</span>
                </#if>
            </div>
            <div class="orchestrator-actions">
                <button class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!}" type="submit">${t.of("Weiter")}</button>
                <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!}"
                        type="submit" name="orchestrator_abandon" value="true">${t.of("Zurück")}</button>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
