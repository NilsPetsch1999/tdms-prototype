const state = {
    sourceConnection: null,
    schemas: [],
    tables: [],
    columns: [],
    datasets: [],
    tableRows: []
};

const techniques = [
    "",
    "SUBSTITUTION",
    "PSEUDONYMIZATION",
    "TOKENIZATION",
    "HASHING",
    "GENERALIZATION"
];

const syntheticRuleStrategies = [
    "",
    "FIXED",
    "CHOICE",
    "RANGE",
    "TEMPLATE",
    "EXPRESSION",
    "AGGREGATE"
];

const els = {
    sourceConnectionForm: document.getElementById("sourceConnectionForm"),
    saveConnectionBtn: document.getElementById("saveConnectionBtn"),
    createProjectBtn: document.getElementById("createProjectBtn"),
    reloadProjectsBtn: document.getElementById("reloadProjectsBtn"),
    schemaSelect: document.getElementById("schemaSelect"),
    tableSelect: document.getElementById("tableSelect"),
    tableDataLimit: document.getElementById("tableDataLimit"),
    tableDataHead: document.querySelector("#tableDataTable thead"),
    tableDataBody: document.querySelector("#tableDataTable tbody"),
    columnsTableBody: document.querySelector("#columnsTable tbody"),
    maskingRulesBody: document.querySelector("#maskingRulesTable tbody"),
    syntheticForm: document.getElementById("syntheticForm"),
    maskedForm: document.getElementById("maskedForm"),
    datasetsContainer: document.getElementById("datasetsContainer"),
    activityLog: document.getElementById("activityLog"),
    tablePlanBody: document.querySelector("#tablePlanTable tbody"),
    syntheticRulesBody: document.querySelector("#syntheticRulesTable tbody")
};

function hasElement(element) {
    return element !== null && element !== undefined;
}

function log(message, payload) {
    if (!hasElement(els.activityLog)) {
        return;
    }
    const stamp = new Date().toISOString();
    const line = `[${stamp}] ${message}`;
    if (payload !== undefined) {
        els.activityLog.textContent = `${line}\n${JSON.stringify(payload, null, 2)}\n\n${els.activityLog.textContent}`;
    } else {
        els.activityLog.textContent = `${line}\n${els.activityLog.textContent}`;
    }
}

async function api(path, options = {}) {
    const response = await fetch(path, {
        headers: { "Content-Type": "application/json", ...(options.headers || {}) },
        ...options
    });
    const contentType = response.headers.get("Content-Type") || "";
    const data = contentType.includes("application/json") ? await response.json() : await response.text();
    if (!response.ok) {
        throw new Error(typeof data === "string" ? data : (data.message || "Request failed"));
    }
    return data;
}

function fillSelect(select, values) {
    if (!hasElement(select)) {
        return;
    }
    select.innerHTML = "";
    values.forEach((value) => {
        const option = document.createElement("option");
        option.value = value;
        option.textContent = value;
        select.appendChild(option);
    });
}

function setFormValues(form, values) {
    if (!hasElement(form)) {
        return;
    }
    Object.entries(values).forEach(([key, value]) => {
        if (form.elements[key]) {
            form.elements[key].value = value ?? "";
        }
    });
}

async function loadSourceConnection() {
    if (!hasElement(els.sourceConnectionForm)) {
        return;
    }
    const connection = await api("/api/source-connection");
    state.sourceConnection = connection;
    setFormValues(els.sourceConnectionForm, connection);
    log("Loaded source connection", {
        url: connection.url,
        username: connection.username,
        databaseProductName: connection.databaseProductName,
        currentCatalog: connection.currentCatalog
    });
}

async function saveSourceConnection() {
    if (!hasElement(els.sourceConnectionForm)) {
        throw new Error("Source connection form is not available on this page.");
    }
    const payload = parseForm(els.sourceConnectionForm);
    const connection = await api("/api/source-connection", {
        method: "PUT",
        body: JSON.stringify(payload)
    });
    state.sourceConnection = connection;
    log("Updated source connection", {
        url: connection.url,
        username: connection.username,
        databaseProductName: connection.databaseProductName,
        currentCatalog: connection.currentCatalog
    });
    await loadSchemas();
    await loadDatasets();
}

async function loadSchemas() {
    if (!hasElement(els.schemaSelect)) {
        return;
    }
    const schemas = await api("/api/schemas");
    state.schemas = schemas;
    fillSelect(els.schemaSelect, schemas);
    log("Loaded schemas", schemas);
    if (schemas.length) {
        await loadTables(schemas[0]);
        applySchemaTableToForms();
    } else {
        state.tables = [];
        state.columns = [];
        state.tableRows = [];
        fillSelect(els.tableSelect, []);
        renderColumns([]);
        renderMaskingRules([]);
        renderTableRows([]);
        applySchemaTableToForms();
    }
}

async function loadTables(schemaName) {
    if (!schemaName || !hasElement(els.tableSelect)) return;
    const tables = await api(`/api/schemas/${encodeURIComponent(schemaName)}/tables`);
    state.tables = tables;
    fillSelect(els.tableSelect, tables);
    log(`Loaded tables for schema ${schemaName}`, tables);
    if (tables.length) {
        await loadColumns(schemaName, tables[0]);
        await loadTableRows(schemaName, tables[0]);
        refreshTablePlanTableOptions();
        applySchemaTableToForms();
    } else {
        state.columns = [];
        state.tableRows = [];
        renderColumns([]);
        renderTableRows([]);
        renderMaskingRules([]);
        if (hasElement(els.tablePlanBody)) {
            els.tablePlanBody.innerHTML = "";
        }
        applySchemaTableToForms();
    }
}

async function loadColumns(schemaName, tableName) {
    if (!schemaName || !tableName) return;
    const columns = await api(`/api/schemas/${encodeURIComponent(schemaName)}/tables/${encodeURIComponent(tableName)}/columns`);
    state.columns = columns;
    renderColumns(columns);
    renderMaskingRules(columns);
    applySchemaTableToForms();
    log(`Loaded columns for ${schemaName}.${tableName}`, columns);
}

async function loadTableRows(schemaName, tableName) {
    if (!schemaName || !tableName) return;
    const limit = Number(els.tableDataLimit?.value || 25);
    const rows = await api(`/api/schemas/${encodeURIComponent(schemaName)}/tables/${encodeURIComponent(tableName)}/rows?limit=${encodeURIComponent(limit)}`);
    state.tableRows = rows;
    renderTableRows(rows);
    log(`Loaded table data for ${schemaName}.${tableName}`, {
        rowCount: rows.length,
        columns: rows[0] ? Object.keys(rows[0]) : []
    });
}

function renderColumns(columns) {
    if (!hasElement(els.columnsTableBody)) {
        return;
    }
    els.columnsTableBody.innerHTML = "";
    columns.forEach((col) => {
        const tr = document.createElement("tr");
        tr.innerHTML = `
            <td>${col.columnName}</td>
            <td>${col.dataType}</td>
            <td>${col.nullable ? "YES" : "NO"}</td>
            <td>${col.primaryKey ? "YES" : "NO"}</td>
            <td>${col.characterMaxLength || col.numericPrecision || "-"}</td>
        `;
        els.columnsTableBody.appendChild(tr);
    });
}

function renderMaskingRules(columns) {
    if (!hasElement(els.maskingRulesBody)) {
        return;
    }
    els.maskingRulesBody.innerHTML = "";
    columns.forEach((col) => {
        const tr = document.createElement("tr");
        const select = document.createElement("select");
        select.dataset.columnName = col.columnName;
        techniques.forEach((technique) => {
            const option = document.createElement("option");
            option.value = technique;
            option.textContent = technique || "KEEP AS IS";
            select.appendChild(option);
        });
        const tdName = document.createElement("td");
        tdName.textContent = col.columnName;
        const tdSelect = document.createElement("td");
        tdSelect.appendChild(select);
        tr.appendChild(tdName);
        tr.appendChild(tdSelect);
        els.maskingRulesBody.appendChild(tr);
    });
}

function renderTableRows(rows) {
    if (!hasElement(els.tableDataHead) || !hasElement(els.tableDataBody)) {
        return;
    }
    els.tableDataHead.innerHTML = "";
    els.tableDataBody.innerHTML = "";
    if (!rows.length) {
        const tr = document.createElement("tr");
        tr.innerHTML = `<td colspan="1">No table data loaded.</td>`;
        els.tableDataBody.appendChild(tr);
        return;
    }

    const columns = Object.keys(rows[0]);
    const headRow = document.createElement("tr");
    columns.forEach((column) => {
        const th = document.createElement("th");
        th.textContent = column;
        headRow.appendChild(th);
    });
    els.tableDataHead.appendChild(headRow);

    rows.forEach((row) => {
        const tr = document.createElement("tr");
        columns.forEach((column) => {
            const td = document.createElement("td");
            const value = row[column];
            td.textContent = value === null || value === undefined ? "" : String(value);
            tr.appendChild(td);
        });
        els.tableDataBody.appendChild(tr);
    });
}

function applySchemaTableToForms() {
    if (!hasElement(els.schemaSelect) || !hasElement(els.tableSelect)) {
        return;
    }
    const schemaName = els.schemaSelect.value;
    const tableName = els.tableSelect.value;
    if (hasElement(els.syntheticForm)) {
        els.syntheticForm.elements.schemaName.value = schemaName;
        els.syntheticForm.elements.tableName.value = tableName;
    }
    if (hasElement(els.maskedForm)) {
        els.maskedForm.elements.schemaName.value = schemaName;
        els.maskedForm.elements.tableName.value = tableName;
    }
    if (hasElement(els.tablePlanBody) && hasElement(els.syntheticForm) && !els.tablePlanBody.children.length && tableName) {
        addTablePlanRow({ include: true, tableName, rows: Number(els.syntheticForm.elements.rowCount.value || 1000) });
    }
}

function parseForm(form) {
    if (!hasElement(form)) {
        return {};
    }
    const values = Object.fromEntries(new FormData(form).entries());
    Object.keys(values).forEach((k) => {
        if (values[k] === "") {
            values[k] = null;
        }
    });
    return values;
}

function collectMaskingRules() {
    if (!hasElement(els.maskingRulesBody)) {
        return {};
    }
    const rules = {};
    els.maskingRulesBody.querySelectorAll("select").forEach((select) => {
        if (select.value) {
            rules[select.dataset.columnName] = select.value;
        }
    });
    return rules;
}

function collectSyntheticRules() {
    if (!hasElement(els.syntheticRulesBody)) {
        return {};
    }
    const rulesByTable = {};
    els.syntheticRulesBody.querySelectorAll("tr").forEach((tr) => {
        const strategy = tr.querySelector(".rule-strategy").value;
        const config = tr.querySelector(".rule-config").value.trim();
        if (!strategy) return;
        const tableName = tr.dataset.tableName;
        const columnName = tr.dataset.columnName;
        if (!rulesByTable[tableName]) {
            rulesByTable[tableName] = {};
        }
        rulesByTable[tableName][columnName] = { strategy, config };
    });
    return rulesByTable;
}

function rulePlaceholder(strategy) {
    switch (strategy) {
        case "FIXED":
            return "e.g. PAID";
        case "CHOICE":
            return "e.g. NEW|PAID|SHIPPED";
        case "RANGE":
            return "e.g. 10|500 or 2024-01-01|2024-12-31";
        case "TEMPLATE":
            return "e.g. ORD-${rowIndex}";
        case "EXPRESSION":
            return "e.g. ${quantity} * ${unit_price}";
        case "AGGREGATE":
            return "e.g. SUM(order_items.line_total BY order_id)";
        default:
            return "Config";
    }
}

function createStrategySelect(selected) {
    const select = document.createElement("select");
    select.className = "rule-strategy";
    syntheticRuleStrategies.forEach((strategy) => {
        const option = document.createElement("option");
        option.value = strategy;
        option.textContent = strategy || "DEFAULT";
        if (strategy === selected) {
            option.selected = true;
        }
        select.appendChild(option);
    });
    return select;
}

function renderSyntheticRules(tableDefinitions, existingRules = {}) {
    if (!hasElement(els.syntheticRulesBody)) {
        return;
    }
    els.syntheticRulesBody.innerHTML = "";
    tableDefinitions.forEach(({ tableName, columns }) => {
        columns.forEach((column) => {
            const rule = existingRules[tableName]?.[column.columnName] || {};
            const tr = document.createElement("tr");
            tr.dataset.tableName = tableName;
            tr.dataset.columnName = column.columnName;

            const tableTd = document.createElement("td");
            tableTd.textContent = tableName;

            const columnTd = document.createElement("td");
            columnTd.textContent = column.columnName;

            const strategyTd = document.createElement("td");
            const strategySelect = createStrategySelect(rule.strategy || "");
            strategyTd.appendChild(strategySelect);

            const configTd = document.createElement("td");
            const configInput = document.createElement("input");
            configInput.className = "rule-config";
            configInput.value = rule.config || "";
            configInput.placeholder = rulePlaceholder(strategySelect.value);
            strategySelect.addEventListener("change", () => {
                configInput.placeholder = rulePlaceholder(strategySelect.value);
            });
            configTd.appendChild(configInput);

            tr.appendChild(tableTd);
            tr.appendChild(columnTd);
            tr.appendChild(strategyTd);
            tr.appendChild(configTd);
            els.syntheticRulesBody.appendChild(tr);
        });
    });
}

async function loadSyntheticRulesForPlan() {
    if (!hasElement(els.syntheticForm) || !hasElement(els.syntheticRulesBody)) {
        return;
    }
    const schemaName = els.syntheticForm.elements.schemaName.value;
    const existingRules = collectSyntheticRules();
    const plan = collectTablePlanPayload();
    const tables = (plan.included.length ? plan.included : [els.syntheticForm.elements.tableName.value]).filter(Boolean);
    const uniqueTables = [...new Set(tables)];
    const definitions = [];
    for (const tableName of uniqueTables) {
        const columns = await api(`/api/schemas/${encodeURIComponent(schemaName)}/tables/${encodeURIComponent(tableName)}/columns`);
        definitions.push({ tableName, columns });
    }
    renderSyntheticRules(definitions, existingRules);
    log("Loaded synthetic column rules", definitions.map((entry) => ({
        tableName: entry.tableName,
        columns: entry.columns.map((column) => column.columnName)
    })));
}

function addTablePlanRow(initial = {}) {
    if (!hasElement(els.tablePlanBody) || !hasElement(els.syntheticForm)) {
        return;
    }
    const tr = document.createElement("tr");

    const includeTd = document.createElement("td");
    const includeInput = document.createElement("input");
    includeInput.type = "checkbox";
    includeInput.checked = initial.include !== false;
    includeTd.appendChild(includeInput);

    const tableTd = document.createElement("td");
    const tableSelect = document.createElement("select");
    tableSelect.className = "plan-table-select";
    state.tables.forEach((table) => {
        const option = document.createElement("option");
        option.value = table;
        option.textContent = table;
        tableSelect.appendChild(option);
    });
    if (initial.tableName) {
        tableSelect.value = initial.tableName;
    }
    tableTd.appendChild(tableSelect);

    const rowsTd = document.createElement("td");
    const rowsInput = document.createElement("input");
    rowsInput.className = "plan-row-input";
    rowsInput.type = "number";
    rowsInput.min = "1";
    rowsInput.max = "200000";
    rowsInput.value = String(initial.rows || els.syntheticForm.elements.rowCount.value || 1000);
    rowsTd.appendChild(rowsInput);

    const actionTd = document.createElement("td");
    const removeBtn = document.createElement("button");
    removeBtn.type = "button";
    removeBtn.className = "plan-remove-btn";
    removeBtn.textContent = "Remove";
    removeBtn.addEventListener("click", () => tr.remove());
    actionTd.appendChild(removeBtn);

    tr.appendChild(includeTd);
    tr.appendChild(tableTd);
    tr.appendChild(rowsTd);
    tr.appendChild(actionTd);
    els.tablePlanBody.appendChild(tr);
}

function refreshTablePlanTableOptions() {
    if (!hasElement(els.tablePlanBody)) {
        return;
    }
    els.tablePlanBody.querySelectorAll("tr").forEach((tr) => {
        const select = tr.querySelector("select");
        if (!select) return;
        const current = select.value;
        select.innerHTML = "";
        state.tables.forEach((table) => {
            const option = document.createElement("option");
            option.value = table;
            option.textContent = table;
            select.appendChild(option);
        });
        if (state.tables.includes(current)) {
            select.value = current;
        }
    });
}

function collectTablePlanPayload() {
    if (!hasElement(els.tablePlanBody)) {
        return { included: [], excluded: [], rowCountByTable: {} };
    }
    const included = [];
    const excluded = [];
    const rowCountByTable = {};

    els.tablePlanBody.querySelectorAll("tr").forEach((tr) => {
        const include = tr.querySelector("input[type='checkbox']").checked;
        const table = tr.querySelector("select").value;
        const rows = Number(tr.querySelector("input[type='number']").value);
        if (!table) return;
        if (include) {
            included.push(table);
            if (Number.isFinite(rows) && rows > 0) {
                rowCountByTable[table] = rows;
            }
        } else {
            excluded.push(table);
        }
    });

    return { included, excluded, rowCountByTable };
}

async function submitSynthetic(event) {
    event.preventDefault();
    if (!hasElement(els.syntheticForm)) {
        throw new Error("Synthetic form is not available on this page.");
    }
    const payload = parseForm(els.syntheticForm);
    payload.rowCount = Number(payload.rowCount);
    if (payload.seed !== null) payload.seed = Number(payload.seed);
    if (payload.defaultRelatedTableRowCount !== null) {
        payload.defaultRelatedTableRowCount = Number(payload.defaultRelatedTableRowCount);
    }
    if (payload.nullableFieldProbability !== null) {
        payload.nullableFieldProbability = Number(payload.nullableFieldProbability);
    }
    payload.generateWholeSchema = els.syntheticForm.elements.generateWholeSchema.checked;
    payload.includeRelatedTables = els.syntheticForm.elements.includeRelatedTables.checked;
    payload.useExistingParentKeys = els.syntheticForm.elements.useExistingParentKeys.checked;
    const plan = collectTablePlanPayload();
    payload.tableNames = plan.included;
    payload.excludedTableNames = plan.excluded;
    payload.rowCountByTable = plan.rowCountByTable;
    const columnRulesByTable = collectSyntheticRules();
    payload.columnRulesByTable = Object.keys(columnRulesByTable).length ? columnRulesByTable : null;
    if (!payload.tableName && payload.tableNames.length) {
        payload.tableName = payload.tableNames[0];
    }
    if (!payload.tableName) payload.tableName = null;

    const result = await api("/api/datasets/synthetic", {
        method: "POST",
        body: JSON.stringify(payload)
    });
    log("Synthetic dataset version created", result);
    await loadDatasets();
}

async function submitMasked(event) {
    event.preventDefault();
    if (!hasElement(els.maskedForm)) {
        throw new Error("Masked form is not available on this page.");
    }
    const payload = parseForm(els.maskedForm);
    payload.rowLimit = Number(payload.rowLimit);
    payload.maskingRules = collectMaskingRules();
    const result = await api("/api/datasets/masked", {
        method: "POST",
        body: JSON.stringify(payload)
    });
    log("Masked dataset version created", result);
    await loadDatasets();
}

async function loadDatasets() {
    const datasets = await api("/api/datasets");
    state.datasets = datasets;
    renderDatasets(datasets);
    log("Loaded datasets", datasets);
}

function renderDatasets(datasets) {
    if (!hasElement(els.datasetsContainer)) {
        return;
    }
    els.datasetsContainer.innerHTML = "";
    if (!datasets.length) {
        els.datasetsContainer.innerHTML = "<p>No datasets yet.</p>";
        return;
    }

    datasets.forEach((dataset) => {
        const card = document.createElement("article");
        card.className = "dataset-card";

        const details = document.createElement("div");
        details.innerHTML = `
            <strong>${dataset.name}</strong>
            <div class="dataset-meta">ID: ${dataset.id} | ${dataset.sourceType} | ${dataset.status}</div>
            <div class="dataset-meta">${dataset.description || "No description"}</div>
        `;
        card.appendChild(details);

        const versionWrap = document.createElement("div");
        versionWrap.className = "chip-row";
        versionWrap.textContent = "Loading versions...";
        card.appendChild(versionWrap);

        const versionDetails = document.createElement("div");
        card.appendChild(versionDetails);

        loadVersions(dataset.id, versionWrap, versionDetails);
        els.datasetsContainer.appendChild(card);
    });
}

async function loadVersions(datasetId, chipContainer, detailsContainer) {
    try {
        const versions = await api(`/api/datasets/${datasetId}/versions`);
        chipContainer.innerHTML = "";
        if (!versions.length) {
            chipContainer.textContent = "No versions";
            return;
        }
        versions.forEach((version) => {
            const chip = document.createElement("button");
            chip.className = "version-chip";
            chip.type = "button";
            chip.textContent = `v${version.versionNumber} (${version.rowCount} rows)`;
            chip.addEventListener("click", () => {
                const extension = version.fileFormat === "ZIP" ? "zip" : "csv";
                detailsContainer.innerHTML = `
                    <div class="dataset-meta">Schema: ${version.schemaName}.${version.tableName}</div>
                    <div class="dataset-meta">Checksum: ${version.checksumSha256}</div>
                    <div class="dataset-meta">Format: ${version.fileFormat}</div>
                    <div class="dataset-meta">Created By: ${version.createdBy || "-"}</div>
                    <a class="inline-link" href="/api/datasets/${datasetId}/versions/${version.versionNumber}/file">Download ${extension.toUpperCase()}</a>
                    <pre>${JSON.stringify(version, null, 2)}</pre>
                `;
            });
            chipContainer.appendChild(chip);
        });
    } catch (error) {
        chipContainer.textContent = "Failed to load versions";
        log(`Failed to load versions for dataset ${datasetId}: ${error.message}`);
    }
}

function bindEvents() {
    if (hasElement(els.sourceConnectionForm)) {
        els.sourceConnectionForm.addEventListener("submit", (event) => {
            event.preventDefault();
            saveSourceConnection().catch(handleError);
        });
    }

    const reloadSchemasBtn = document.getElementById("reloadSchemasBtn");
    if (reloadSchemasBtn) {
        reloadSchemasBtn.addEventListener("click", () => loadSchemas().catch(handleError));
    }

    const reloadDatasetsBtn = document.getElementById("reloadDatasetsBtn");
    if (reloadDatasetsBtn) {
        reloadDatasetsBtn.addEventListener("click", () => loadDatasets().catch(handleError));
    }

    if (hasElement(els.reloadProjectsBtn)) {
        els.reloadProjectsBtn.addEventListener("click", () => loadDatasets().catch(handleError));
    }

    const loadColumnsBtn = document.getElementById("loadColumnsBtn");
    if (loadColumnsBtn) {
        loadColumnsBtn.addEventListener("click", () => {
            loadColumns(els.schemaSelect?.value, els.tableSelect?.value).catch(handleError);
        });
    }

    const loadTableDataBtn = document.getElementById("loadTableDataBtn");
    if (loadTableDataBtn) {
        loadTableDataBtn.addEventListener("click", () => {
            loadTableRows(els.schemaSelect?.value, els.tableSelect?.value).catch(handleError);
        });
    }

    const useSelectionBtn = document.getElementById("useSelectionBtn");
    if (useSelectionBtn) {
        useSelectionBtn.addEventListener("click", applySchemaTableToForms);
    }

    const addTablePlanRowBtn = document.getElementById("addTablePlanRowBtn");
    if (addTablePlanRowBtn) {
        addTablePlanRowBtn.addEventListener("click", () => {
            addTablePlanRow({ include: true, tableName: els.tableSelect?.value, rows: Number(els.syntheticForm?.elements.rowCount.value || 1000) });
        });
    }

    const loadAllTablesPlanBtn = document.getElementById("loadAllTablesPlanBtn");
    if (loadAllTablesPlanBtn) {
        loadAllTablesPlanBtn.addEventListener("click", () => {
            if (hasElement(els.tablePlanBody)) {
                els.tablePlanBody.innerHTML = "";
            }
            state.tables.forEach((table) => addTablePlanRow({ include: true, tableName: table, rows: Number(els.syntheticForm?.elements.rowCount.value || 1000) }));
        });
    }

    const loadSyntheticRulesBtn = document.getElementById("loadSyntheticRulesBtn");
    if (loadSyntheticRulesBtn) {
        loadSyntheticRulesBtn.addEventListener("click", () => {
            loadSyntheticRulesForPlan().catch(handleError);
        });
    }

    const loadMaskingColumnsBtn = document.getElementById("loadMaskingColumnsBtn");
    if (loadMaskingColumnsBtn && hasElement(els.maskedForm)) {
        loadMaskingColumnsBtn.addEventListener("click", () => {
            const schemaName = els.maskedForm.elements.schemaName.value;
            const tableName = els.maskedForm.elements.tableName.value;
            loadColumns(schemaName, tableName).catch(handleError);
        });
    }

    if (hasElement(els.schemaSelect)) {
        els.schemaSelect.addEventListener("change", () => {
            loadTables(els.schemaSelect.value).catch(handleError);
        });
    }

    if (hasElement(els.tableSelect)) {
        els.tableSelect.addEventListener("change", () => {
            loadColumns(els.schemaSelect?.value, els.tableSelect.value).catch(handleError);
            loadTableRows(els.schemaSelect?.value, els.tableSelect.value).catch(handleError);
        });
    }

    if (hasElement(els.syntheticForm)) {
        els.syntheticForm.addEventListener("submit", (event) => {
            submitSynthetic(event).catch(handleError);
        });
    }

    const createPythonProjectBtn = document.getElementById("createPythonProjectBtn");
    if (createPythonProjectBtn) {
        createPythonProjectBtn.addEventListener("click", () => {
            submitPythonSynthetic().catch(handleError);
        });
    }

    if (hasElement(els.maskedForm)) {
        els.maskedForm.addEventListener("submit", (event) => {
            submitMasked(event).catch(handleError);
        });
    }

    document.addEventListener("click", (event) => {
        const target = event.target;
        if (!(target instanceof HTMLElement)) {
            return;
        }

        const label = (target.textContent || "").trim().toLowerCase();
        const id = target.id || "";

        if (id === "createProjectBtn" || id === "createProject" || label === "create project") {
            event.preventDefault();
            window.createProject().catch(handleError);
            return;
        }

        if (id === "reloadProjectsBtn" || id === "reloadProjectBtn" || label === "reload projects" || label === "reload project") {
            event.preventDefault();
            loadDatasets().catch(handleError);
        }
    });
}

function handleError(error) {
    if (typeof console !== "undefined" && console.error) {
        console.error(error);
    }
    log(`ERROR: ${error.message}`);
}

function getValueByCandidates(candidates) {
    for (const candidate of candidates) {
        const byId = document.getElementById(candidate);
        if (byId && typeof byId.value !== "undefined" && String(byId.value).trim() !== "") {
            return String(byId.value).trim();
        }
        const byName = document.querySelector(`[name="${candidate}"]`);
        if (byName && typeof byName.value !== "undefined" && String(byName.value).trim() !== "") {
            return String(byName.value).trim();
        }
    }
    return null;
}

async function submitSyntheticCompatibilityPayload(payload) {
    const result = await api("/api/datasets/synthetic", {
        method: "POST",
        body: JSON.stringify(payload)
    });
    log("Synthetic dataset version created", result);
    await loadDatasets();
    return result;
}

async function submitPythonSynthetic() {
    if (!hasElement(els.syntheticForm)) {
        throw new Error("Synthetic form is not available on this page.");
    }
    if (!els.syntheticForm.reportValidity()) {
        throw new Error("Please fill in the required synthetic project fields first.");
    }

    const payload = parseForm(els.syntheticForm);
    payload.rowCount = Number(payload.rowCount);

    if (!payload.schemaName || !payload.tableName) {
        throw new Error("Python CTGAN generation requires a schema and one primary table.");
    }
    if (els.syntheticForm.elements.generateWholeSchema?.checked) {
        throw new Error("Python CTGAN generation currently supports a single table only. Disable 'Generate Whole Schema'.");
    }

    const result = await api("/api/datasets/synthetic/python", {
        method: "POST",
        body: JSON.stringify({
            datasetName: payload.datasetName,
            description: payload.description,
            schemaName: payload.schemaName,
            tableName: payload.tableName,
            rowCount: payload.rowCount,
            schemaVersion: payload.schemaVersion,
            createdBy: payload.createdBy
        })
    });
    log("Python CTGAN dataset version created", result);
    await loadDatasets();
    return result;
}

// Compatibility shim for older cached HTML that still uses inline onclick="createProject()".
window.__tdmsCreateProjectImpl = async function createProject() {
    try {
        if (hasElement(els.syntheticForm)) {
            if (!els.syntheticForm.reportValidity()) {
                throw new Error("Please fill in the required project fields first.");
            }
            const payload = parseForm(els.syntheticForm);
            payload.rowCount = Number(payload.rowCount);
            if (payload.seed !== null) payload.seed = Number(payload.seed);
            if (payload.defaultRelatedTableRowCount !== null) {
                payload.defaultRelatedTableRowCount = Number(payload.defaultRelatedTableRowCount);
            }
            if (payload.nullableFieldProbability !== null) {
                payload.nullableFieldProbability = Number(payload.nullableFieldProbability);
            }
            payload.generateWholeSchema = !!els.syntheticForm.elements.generateWholeSchema?.checked;
            payload.includeRelatedTables = els.syntheticForm.elements.includeRelatedTables?.checked ?? true;
            payload.useExistingParentKeys = els.syntheticForm.elements.useExistingParentKeys?.checked ?? true;
            const plan = collectTablePlanPayload();
            payload.tableNames = plan.included;
            payload.excludedTableNames = plan.excluded;
            payload.rowCountByTable = plan.rowCountByTable;
            const columnRulesByTable = collectSyntheticRules();
            payload.columnRulesByTable = Object.keys(columnRulesByTable).length ? columnRulesByTable : null;
            if (!payload.tableName && payload.tableNames.length) {
                payload.tableName = payload.tableNames[0];
            }
            return await submitSyntheticCompatibilityPayload(payload);
        }

        const payload = {
            datasetName: getValueByCandidates(["datasetName", "projectName", "name"]),
            description: getValueByCandidates(["description", "projectDescription"]),
            schemaName: getValueByCandidates(["schemaName", "schema"]),
            tableName: getValueByCandidates(["tableName", "table", "primaryTable"]),
            rowCount: Number(getValueByCandidates(["rowCount", "rows", "projectRowCount"]) || 1000),
            schemaVersion: getValueByCandidates(["schemaVersion"]),
            createdBy: getValueByCandidates(["createdBy", "owner", "user"]),
            generateWholeSchema: false,
            includeRelatedTables: true,
            useExistingParentKeys: true,
            tableNames: [],
            excludedTableNames: [],
            rowCountByTable: null,
            columnRulesByTable: null,
            seed: null,
            defaultRelatedTableRowCount: null,
            nullableFieldProbability: null
        };

        if (!payload.datasetName || !payload.schemaName || !payload.tableName) {
            throw new Error("Project creation requires dataset/project name, schema name, and table name.");
        }

        return await submitSyntheticCompatibilityPayload(payload);
    } catch (error) {
        handleError(error);
        throw error;
    }
};
window.createProject = window.__tdmsCreateProjectImpl;

window.__tdmsCreateConnectionImpl = function createConnection() {
    saveSourceConnection().catch(handleError);
};
window.createConnection = window.__tdmsCreateConnectionImpl;

if (window.__tdmsPendingAction === "createProject") {
    window.__tdmsPendingAction = null;
    window.createProject().catch(handleError);
}

if (window.__tdmsPendingAction === "createConnection") {
    window.__tdmsPendingAction = null;
    window.createConnection();
}

async function init() {
    bindEvents();
    try {
        try {
            await loadSourceConnection();
        } catch (error) {
            log(`Source connection endpoint unavailable: ${error.message}`);
        }
        await loadSchemas();
        applySchemaTableToForms();
        if (!els.tablePlanBody.children.length && state.tables.length) {
            addTablePlanRow({ include: true, tableName: state.tables[0], rows: Number(els.syntheticForm.elements.rowCount.value || 1000) });
        }
        await loadDatasets();
        log("UI initialized");
    } catch (error) {
        handleError(error);
    }
}

init();
