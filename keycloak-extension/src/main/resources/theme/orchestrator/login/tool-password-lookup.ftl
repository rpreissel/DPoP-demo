<#import "template.ftl" as layout>
<#import "demo-person-picker.ftl" as demoPerson>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "form">
        <h2 class="${properties.kcFormHeaderClass!}">${title}</h2>
        <p class="${properties.kcLabelClass!}">${hint}</p>
        <form id="kc-orchestrator-tool-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <@demoPerson.personPicker personsJson=demoPersonsJson! fieldMapJson='{"email":"email"}' />
            <div class="${properties.kcFormGroupClass!}">
                <label for="email" class="${properties.kcLabelClass!}">E-Mail-Adresse</label>
                <input type="email" id="email" name="email" class="${properties.kcInputClass!}" autocomplete="off"/>
                <#if demoEmail??>
                    <span class="${properties.kcLabelClass!}">Demo-E-Mail: ${demoEmail}</span>
                </#if>
            </div>
            <div class="${properties.kcFormGroupClass!}">
                <label for="password" class="${properties.kcLabelClass!}">Passwort</label>
                <input type="password" id="password" name="password" class="${properties.kcInputClass!}" autocomplete="off"/>
                <#if demoPassword??>
                    <span class="${properties.kcLabelClass!}">Demo-Passwort: ${demoPassword}</span>
                </#if>
            </div>
            <div class="${properties.kcFormGroupClass!}">
                <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!} ${properties.kcButtonBlockClass!}"
                        type="submit">Weiter</button>
            </div>
            <div class="${properties.kcFormGroupClass!}">
                <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!} ${properties.kcButtonBlockClass!}"
                        type="submit" name="orchestrator_abandon" value="true">Zurück</button>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
