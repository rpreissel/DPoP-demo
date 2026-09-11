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
            <#if step == "card">
                <p class="orchestrator-hint">Demo-Modus: Das Auslesen der Karte wird simuliert; Testdaten sind bereits vorbelegt.</p>
                <@demoPerson.personPicker personsJson=demoPersonsJson! fieldMapJson='{"geburtsdatum":"geburtsdatum","strasse":"strasse","hausnummer":"hausnummer","plz":"plz","ort":"ort"}' />
                <div class="${properties.kcFormGroupClass!}">
                    <label for="geburtsdatum" class="${properties.kcLabelClass!}">Geburtsdatum</label>
                    <input type="date" id="geburtsdatum" name="geburtsdatum" class="${properties.kcInputClass!}" value="1985-06-15"/>
                </div>
                <div class="orchestrator-grid-2">
                    <div class="${properties.kcFormGroupClass!}">
                        <label for="strasse" class="${properties.kcLabelClass!}">Straße</label>
                        <input type="text" id="strasse" name="strasse" class="${properties.kcInputClass!}" value="Musterstraße"/>
                    </div>
                    <div class="${properties.kcFormGroupClass!}">
                        <label for="hausnummer" class="${properties.kcLabelClass!}">Hausnummer</label>
                        <input type="text" id="hausnummer" name="hausnummer" class="${properties.kcInputClass!}" value="1"/>
                    </div>
                </div>
                <div class="orchestrator-grid-2">
                    <div class="${properties.kcFormGroupClass!}">
                        <label for="plz" class="${properties.kcLabelClass!}">PLZ</label>
                        <input type="text" id="plz" name="plz" class="${properties.kcInputClass!}" value="12345"/>
                    </div>
                    <div class="${properties.kcFormGroupClass!}">
                        <label for="ort" class="${properties.kcLabelClass!}">Ort</label>
                        <input type="text" id="ort" name="ort" class="${properties.kcInputClass!}" value="Musterstadt"/>
                    </div>
                </div>
            <#elseif step == "pin">
                <p class="orchestrator-hint">Testdaten vorbelegt: PIN 123456</p>
                <div class="${properties.kcFormGroupClass!}">
                    <label for="pin" class="${properties.kcLabelClass!}">eID-PIN</label>
                    <input type="text" id="pin" name="pin" class="${properties.kcInputClass!}" autocomplete="off" value="123456"/>
                </div>
            <#else>
                <p class="orchestrator-hint">Testdaten vorbelegt: A123456789 / Muster, Max</p>
                <@demoPerson.personPicker personsJson=demoPersonsJson! fieldMapJson='{"kvnr":"kvnr","name":"name","vorname":"vorname"}' />
                <div class="${properties.kcFormGroupClass!}">
                    <label for="kvnr" class="${properties.kcLabelClass!}">Krankenversichertennummer</label>
                    <input type="text" id="kvnr" name="kvnr" class="${properties.kcInputClass!}" value="A123456789"/>
                </div>
                <div class="orchestrator-grid-2">
                    <div class="${properties.kcFormGroupClass!}">
                        <label for="name" class="${properties.kcLabelClass!}">Nachname</label>
                        <input type="text" id="name" name="name" class="${properties.kcInputClass!}" value="Muster"/>
                    </div>
                    <div class="${properties.kcFormGroupClass!}">
                        <label for="vorname" class="${properties.kcLabelClass!}">Vorname</label>
                        <input type="text" id="vorname" name="vorname" class="${properties.kcInputClass!}" value="Max"/>
                    </div>
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
