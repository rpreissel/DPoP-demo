<#-- Shared by every tool-*.ftl that offers a demo persona picker (mirrors the App channel's
     DemoPersonPicker.tsx): a free-text input stays free-text always (manual input must always be
     possible), this only adds a <select> above it that, on change, fills the given input ids from
     one of demo.persons' fields. personsJson comes from AbstractWebToolRendererFactory#demoPersonsJson;
     fieldMapJson is a JSON object literal mapping input-element-id -> person field name, e.g.
     '{"email":"email"}' or '{"kvnr":"kvnr","name":"name","vorname":"vorname","fsc":"fscCode"}'. -->
<#macro personPicker personsJson fieldMapJson>
    <#if personsJson?? && personsJson != "null">
        <div class="${properties.kcFormGroupClass!} orchestrator-demo-picker">
            <label for="demoPerson" class="${properties.kcLabelClass!}">Demo-Person</label>
            <select id="demoPerson" class="${properties.kcInputClass!}">
                <option value="">— manuell eingeben —</option>
            </select>
        </div>
        <script>
            (function () {
                var persons = ${personsJson?no_esc};
                var fieldMap = ${fieldMapJson?no_esc};
                var select = document.getElementById('demoPerson');
                persons.forEach(function (p, i) {
                    var opt = document.createElement('option');
                    opt.value = String(i);
                    opt.textContent = p.vorname + ' ' + p.name + ' (' + (p.email || p.kvnr) + ')';
                    select.appendChild(opt);
                });
                select.addEventListener('change', function () {
                    if (select.value === '') return;
                    var p = persons[parseInt(select.value, 10)];
                    Object.keys(fieldMap).forEach(function (inputId) {
                        var el = document.getElementById(inputId);
                        var key = fieldMap[inputId];
                        if (el && p[key] !== undefined) el.value = p[key];
                    });
                });
            })();
        </script>
    </#if>
</#macro>
