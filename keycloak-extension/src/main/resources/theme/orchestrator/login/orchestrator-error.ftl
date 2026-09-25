<#import "template.ftl" as layout>
<#import "made-with.ftl" as madeWith>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "header">
        ${t.of("Anmeldung nicht möglich")}
    <#elseif section = "form">
        <@madeWith.note/>
    </#if>
</@layout.registrationLayout>
