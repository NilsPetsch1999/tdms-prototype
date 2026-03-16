const state = {
    schemas: [],
    tables: [],
    columns: [],
    datasets: []
};

const techniques = [
    "",
    "SUBSTITUTION",
    "PSEUDONYMIZATION",
    "TOKENIZATION",
    "HASHING",
    "GENERALIZATION"
];

const els = {
    schemaSelect: document.getElementById("schemaSelect"),
    tableSelect: document.getElementById("tableSelect"),
    columnsTableBody: document.querySelector("#columnsTable tbody"),
    maskingRulesBody: document.querySelector("#maskingRulesTable tbody"),
    syntheticForm: document.getElementById("syntheticForm"),
    maskedForm: document.getElementById("maskedForm"),
    datasetsContainer: document.getElementById("datasetsContainer"),
    activityLog: document.getElementById("activityLog"),
    tablePlanBody: document.querySelector("#tablePlanTable tbody")
};

function log(message, payload) {
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
    select.innerHTML = "";
    values.forEach((value) => {
        const option = document.createElement("option");
        option.value = value;
        option.textContent = value;
        select.appendChild(option);
    });
}

async function loadSchemas() {
    const schemas = await api("/api/schemas");
    state.schemas = schemas;
    fillSelect(els.schemaSelect, schemas);
    log("Loaded schemas", schemas);
    if (schemas.length) {
        await loadTables(schemas[0]);
    }
}

async function loadTables(schemaName) {
    if (!schemaName) return;
    const tables = await api(`/api/schemas/${encodeURIComponent(schemaName)}/tables`);
    state.tables = tables;
    fillSelect(els.tableSelect, tables);
    log(`Loaded tables for schema ${schemaName}`, tables);
    if (tables.length) {
        await loadColumns(schemaName, tables[0]);
        refreshTablePlanTableOptions();
    } else {
        state.columns = [];
        renderColumns([]);
        renderMaskingRules([]);
        els.tablePlanBody.innerHTML = "";
    }
}

async function loadColumns(schemaName, tableName) {
    if (!schemaName || !tableName) return;
    const columns = await api(`/api/schemas/${encodeURIComponent(schemaName)}/tables/${encodeURIComponent(tableName)}/columns`);
    state.columns = columns;
    renderColumns(columns);
    renderMaskingRules(columns);
    log(`Loaded columns for ${schemaName}.${tableName}`, columns);
}

function renderColumns(columns) {
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

function applySchemaTableToForms() {
    const schemaName = els.schemaSelect.value;
    const tableName = els.tableSelect.value;
    els.syntheticForm.elements.schemaName.value = schemaName;
    els.syntheticForm.elements.tableName.value = tableName;
    els.maskedForm.elements.schemaName.value = schemaName;
    els.maskedForm.elements.tableName.value = tableName;
    if (!els.tablePlanBody.children.length && tableName) {
        addTablePlanRow({ include: true, tableName, rows: Number(els.syntheticForm.elements.rowCount.value || 1000) });
    }
}

function parseForm(form) {
    const values = Object.fromEntries(new FormData(form).entries());
    Object.keys(values).forEach((k) => {
        if (values[k] === "") {
            values[k] = null;
        }
    });
    return values;
}

function collectMaskingRules() {
    const rules = {};
    els.maskingRulesBody.querySelectorAll("select").forEach((select) => {
        if (select.value) {
            rules[select.dataset.columnName] = select.value;
        }
    });
    return rules;
}

function addTablePlanRow(initial = {}) {
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
    document.getElementById("reloadSchemasBtn").addEventListener("click", () => loadSchemas().catch(handleError));
    document.getElementById("reloadDatasetsBtn").addEventListener("click", () => loadDatasets().catch(handleError));
    document.getElementById("loadColumnsBtn").addEventListener("click", () => {
        loadColumns(els.schemaSelect.value, els.tableSelect.value).catch(handleError);
    });
    document.getElementById("useSelectionBtn").addEventListener("click", applySchemaTableToForms);
    document.getElementById("addTablePlanRowBtn").addEventListener("click", () => {
        addTablePlanRow({ include: true, tableName: els.tableSelect.value, rows: Number(els.syntheticForm.elements.rowCount.value || 1000) });
    });
    document.getElementById("loadAllTablesPlanBtn").addEventListener("click", () => {
        els.tablePlanBody.innerHTML = "";
        state.tables.forEach((table) => addTablePlanRow({ include: true, tableName: table, rows: Number(els.syntheticForm.elements.rowCount.value || 1000) }));
    });
    document.getElementById("loadMaskingColumnsBtn").addEventListener("click", () => {
        const schemaName = els.maskedForm.elements.schemaName.value;
        const tableName = els.maskedForm.elements.tableName.value;
        loadColumns(schemaName, tableName).catch(handleError);
    });

    els.schemaSelect.addEventListener("change", () => {
        loadTables(els.schemaSelect.value).catch(handleError);
    });
    els.tableSelect.addEventListener("change", () => {
        loadColumns(els.schemaSelect.value, els.tableSelect.value).catch(handleError);
    });

    els.syntheticForm.addEventListener("submit", (event) => {
        submitSynthetic(event).catch(handleError);
    });
    els.maskedForm.addEventListener("submit", (event) => {
        submitMasked(event).catch(handleError);
    });
}

function handleError(error) {
    log(`ERROR: ${error.message}`);
}

async function init() {
    bindEvents();
    try {
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
