<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "header">
        Anmeldeverfahren verwalten
    <#elseif section = "form">
        <#if methods?size == 0>
            <p class="orchestrator-hint">Noch keine Anmeldeverfahren aktiv.</p>
        <#else>
            <#list methods as m>
                <div class="orchestrator-method-row">
                    <span class="${properties.kcLabelClass!}">${m.label!m.method}</span>
                    <form action="${url.loginAction}" method="post">
                        <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!}"
                                type="submit" name="removeMethodInstanceId" value="${m.id}">Entfernen</button>
                    </form>
                </div>
            </#list>
        </#if>

        <div class="orchestrator-actions">
            <form action="${url.loginAction}" method="post">
                <button class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!}"
                        type="submit" name="action" value="add">Neues Verfahren hinzufügen</button>
            </form>
            <form action="${url.loginAction}" method="post">
                <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!} ${properties.kcButtonBlockClass!}"
                        type="submit" name="action" value="done">Fertig</button>
            </form>
        </div>
    </#if>
</@layout.registrationLayout>
