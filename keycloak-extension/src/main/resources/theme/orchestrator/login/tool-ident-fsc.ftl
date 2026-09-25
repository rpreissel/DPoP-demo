<#import "template.ftl" as layout>
<#import "page-notes.ftl" as pageNotes>
<#import "demo-person-picker.ftl" as demoPerson>
<#-- Two pages of one backend step (IdentFscRendererFactory): personal data first, then the code.
     "Zurück" on the code page is this page's own business, like "Angaben ändern" in the App: it
     shows the personal data again, and sending them again has the backend check them anew and ask
     for the code once more. Only "Zurück" on the personal data leaves the tool (orchestrator_back). -->
<#macro personalForm formId pickerId backToCode>
    <p class="orchestrator-subtitle">${t.of("Damit Sie Ihren Freischaltcode gleich eingeben können, brauchen wir noch diese Daten:")}</p>
    <form id="${formId}" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
        <@demoPerson.personPicker personsJson=demoPersonsJson! pickerId=pickerId fieldMapJson='{"vorname":"vorname","name":"name","geburtsdatum":"geburtsdatum","kvnr":"kvnr","partnernr":"personId"}' />
        <div class="${properties.kcFormGroupClass!}">
            <label for="vorname" class="${properties.kcLabelClass!}">${t.of("Vorname")}</label>
            <input type="text" id="vorname" name="vorname" class="${properties.kcInputClass!}" autocomplete="given-name" required/>
        </div>
        <div class="${properties.kcFormGroupClass!}">
            <label for="name" class="${properties.kcLabelClass!}">${t.of("Nachname")}</label>
            <input type="text" id="name" name="name" class="${properties.kcInputClass!}" autocomplete="family-name" required/>
        </div>
        <div class="${properties.kcFormGroupClass!}">
            <label for="geburtsdatum" class="${properties.kcLabelClass!}">${t.of("Geburtsdatum")}</label>
            <input type="date" id="geburtsdatum" name="geburtsdatum" class="${properties.kcInputClass!}" autocomplete="bday" required/>
        </div>
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
            <button class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!}" type="submit">${t.of("Weiter zur Freischaltcode-Eingabe")}</button>
            <#if backToCode>
                <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!}"
                        type="button" onclick="showFscPage('code')">${t.of("Zurück")}</button>
            <#else>
                <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!}"
                        type="submit" name="orchestrator_back" value="true" formnovalidate>${t.of("Zurück")}</button>
            </#if>
        </div>
    </form>
</#macro>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "header">
        ${pageTitle}
    <#elseif section = "form">
        <@pageNotes.notes/>
        <#if personalienPage>
            <@personalForm formId="kc-orchestrator-tool-form" pickerId="demoPerson" backToCode=false/>
        <#else>
            <div id="fsc-code-page">
                <p class="orchestrator-subtitle">${t.of("Geben Sie den Freischaltcode ein, den wir Ihnen per Brief geschickt haben.")}</p>
                <form id="kc-orchestrator-tool-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
                    <@demoPerson.personPicker personsJson=demoPersonsJson! pickerId="demoPersonCode" fieldMapJson='{"fsc":"fscCode"}' />
                    <div class="${properties.kcFormGroupClass!}">
                        <label for="fsc" class="${properties.kcLabelClass!}">${t.of("Freischaltcode")}</label>
                        <input type="text" id="fsc" name="fsc" class="${properties.kcInputClass!}" autocomplete="one-time-code" required/>
                    </div>
                    <div class="orchestrator-actions">
                        <button class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!}" type="submit">${t.of("Identifizieren")}</button>
                        <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!}"
                                type="button" onclick="showFscPage('personal')">${t.of("Zurück")}</button>
                    </div>
                </form>
            </div>
            <div id="fsc-personal-page" hidden>
                <@personalForm formId="kc-orchestrator-tool-form-personal" pickerId="demoPersonPersonal" backToCode=true/>
            </div>
            <script>
                function showFscPage(page) {
                    document.getElementById('fsc-code-page').hidden = page !== 'code';
                    document.getElementById('fsc-personal-page').hidden = page !== 'personal';
                }
            </script>
        </#if>
    </#if>
</@layout.registrationLayout>
