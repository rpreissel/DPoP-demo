<#import "template.ftl" as layout>
<#import "demo-person-picker.ftl" as demoPerson>
<#-- Two fixed pages (IdentFscRendererFactory): personal data first, then the code. -->
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "header">
        ${title}
    <#elseif section = "form">
        <#if personalienPage>
            <p class="orchestrator-subtitle">${t.of("Damit Sie Ihren Freischaltcode gleich eingeben können, brauchen wir noch diese Daten:")}</p>
            <form id="kc-orchestrator-tool-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
                <@demoPerson.personPicker personsJson=demoPersonsJson! fieldMapJson='{"vorname":"vorname","name":"name","geburtsdatum":"geburtsdatum","kvnr":"kvnr"}' />
                <div class="${properties.kcFormGroupClass!}">
                    <label for="vorname" class="${properties.kcLabelClass!}">${t.of("Vorname")}</label>
                    <input type="text" id="vorname" name="vorname" class="${properties.kcInputClass!}" value="Max" autocomplete="given-name" required/>
                </div>
                <div class="${properties.kcFormGroupClass!}">
                    <label for="name" class="${properties.kcLabelClass!}">${t.of("Nachname")}</label>
                    <input type="text" id="name" name="name" class="${properties.kcInputClass!}" value="Muster" autocomplete="family-name" required/>
                </div>
                <div class="${properties.kcFormGroupClass!}">
                    <label for="geburtsdatum" class="${properties.kcLabelClass!}">${t.of("Geburtsdatum")}</label>
                    <input type="date" id="geburtsdatum" name="geburtsdatum" class="${properties.kcInputClass!}" value="1985-06-15" autocomplete="bday" required/>
                </div>
                <div class="${properties.kcFormGroupClass!}">
                    <label for="kvnr" class="${properties.kcLabelClass!}">${t.of("Versichertennummer")}</label>
                    <input type="text" id="kvnr" name="kvnr" class="${properties.kcInputClass!}" value="A123456789" required/>
                </div>
                <div class="orchestrator-actions">
                    <button class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!}" type="submit">${t.of("Weiter zur Freischaltcode-Eingabe")}</button>
                    <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!}"
                            type="submit" name="orchestrator_abandon" value="true" formnovalidate>${t.of("Zurück")}</button>
                </div>
            </form>
        <#else>
            <p class="orchestrator-subtitle">${t.of("Geben Sie den Freischaltcode ein, den wir Ihnen per Brief geschickt haben.")}</p>
            <form id="kc-orchestrator-tool-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
                <@demoPerson.personPicker personsJson=demoPersonsJson! fieldMapJson='{"fsc":"fscCode"}' />
                <div class="${properties.kcFormGroupClass!}">
                    <label for="fsc" class="${properties.kcLabelClass!}">${t.of("Freischaltcode")}</label>
                    <input type="text" id="fsc" name="fsc" class="${properties.kcInputClass!}" value="VALIDCODE" autocomplete="one-time-code" required/>
                </div>
                <div class="orchestrator-actions">
                    <button class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!}" type="submit">${t.of("Identifizieren")}</button>
                    <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!}"
                            type="submit" name="orchestrator_abandon" value="true" formnovalidate>${t.of("Zurück")}</button>
                </div>
            </form>
        </#if>
    </#if>
</@layout.registrationLayout>
