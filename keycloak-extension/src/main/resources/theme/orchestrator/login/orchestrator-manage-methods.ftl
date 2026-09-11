<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "header">
        Anmeldeverfahren verwalten
    <#elseif section = "form">
        <#if methods?size == 0>
            <p class="${properties.kcLabelClass!}">Noch keine Anmeldeverfahren aktiv.</p>
        <#else>
            <#list methods as m>
                <div class="${properties.kcFormGroupClass!}" style="display:flex; align-items:center; justify-content:space-between;">
                    <span class="${properties.kcLabelClass!}">${m.label!m.method}</span>
                    <form action="${url.loginAction}" method="post">
                        <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!}"
                                type="submit" name="removeMethodInstanceId" value="${m.id}">Entfernen</button>
                    </form>
                </div>
            </#list>
        </#if>

        <form action="${url.loginAction}" method="post">
            <div class="${properties.kcFormGroupClass!}">
                <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!} ${properties.kcButtonBlockClass!}"
                        type="submit" name="action" value="add">Neues Verfahren hinzufügen</button>
            </div>
        </form>

        <form action="${url.loginAction}" method="post">
            <div class="${properties.kcFormGroupClass!}">
                <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!} ${properties.kcButtonBlockClass!}"
                        type="submit" name="action" value="done">Fertig</button>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
