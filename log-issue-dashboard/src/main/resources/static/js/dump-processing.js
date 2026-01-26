// Dump Processing Management JavaScript

// State
let isAdmin = true; // Admin is always enabled for dump processing
let configs = [];
let selectedConfigId = null;
let deleteConfigId = null;
let currentFiles = []; // Store current files for filtering
let currentFilter = 'ALL'; // Current filter status

// DOM Elements
const configTableBody = document.getElementById('configTableBody');
const filesTableBody = document.getElementById('filesTableBody');
const filesSection = document.getElementById('filesSection');
const selectedConfigName = document.getElementById('selectedConfigName');

// Status elements
const watcherStatus = document.getElementById('watcherStatus');
const configCount = document.getElementById('configCount');
const pendingCount = document.getElementById('pendingCount');
const processingCount = document.getElementById('processingCount');
const completedCount = document.getElementById('completedCount');
const failedCount = document.getElementById('failedCount');

// Admin elements
const adminSection = document.getElementById('adminSection');
const adminToggle = document.getElementById('adminToggle');
const adminForm = document.getElementById('adminForm');
const adminStatus = document.getElementById('adminStatus');
const adminUsername = document.getElementById('adminUsername');
const adminPassword = document.getElementById('adminPassword');
const loginBtn = document.getElementById('loginBtn');

// Modal elements
const configModal = document.getElementById('configModal');
const modalTitle = document.getElementById('modalTitle');
const configForm = document.getElementById('configForm');
const configIdInput = document.getElementById('configId');
const serverNameInput = document.getElementById('serverName');
const dbTypeInput = document.getElementById('dbType');
const dbFolderInput = document.getElementById('dbFolder');
const dumpFolderInput = document.getElementById('dumpFolder');
const javaPathInput = document.getElementById('javaPath');
const thresholdMinutesInput = document.getElementById('thresholdMinutes');
const adminUserInput = document.getElementById('suAdminUser');
const adminPasswordInput = document.getElementById('suAdminPassword');
const passwordHint = document.getElementById('passwordHint');
const enabledInput = document.getElementById('enabled');
const commandPreviewText = document.getElementById('commandPreviewText');
const validationResult = document.getElementById('validationResult');

// Buttons
const addConfigBtn = document.getElementById('addConfigBtn');
const modalClose = document.getElementById('modalClose');
const cancelBtn = document.getElementById('cancelBtn');
const saveBtn = document.getElementById('saveBtn');
const validateBtn = document.getElementById('validateBtn');
const closeFilesBtn = document.getElementById('closeFilesBtn');

// Delete modal
const deleteModal = document.getElementById('deleteModal');
const deleteModalClose = document.getElementById('deleteModalClose');
const deleteCancelBtn = document.getElementById('deleteCancelBtn');
const deleteConfirmBtn = document.getElementById('deleteConfirmBtn');

// Output modal
const outputModal = document.getElementById('outputModal');
const outputModalClose = document.getElementById('outputModalClose');
const outputContent = document.getElementById('outputContent');
const outputCloseBtn = document.getElementById('outputCloseBtn');

// Theme toggle
const themeToggle = document.getElementById('themeToggle');

// Initialize
document.addEventListener('DOMContentLoaded', () => {
    loadConfigs();
    loadStatus();
    setupEventListeners();
    loadTheme();
    
    // Show admin columns by default (no authentication required for dump processing)
    showAdminColumns();
    
    // Hide admin login section since it's not needed
    if (adminSection) {
        adminSection.style.display = 'none';
    }
    
    // Auto-refresh status every 30 seconds
    setInterval(loadStatus, 30000);
    
    // Auto-refresh configs every 60 seconds
    setInterval(loadConfigs, 60000);
});

function setupEventListeners() {
    // Admin toggle
    adminToggle.addEventListener('click', () => {
        adminForm.style.display = adminForm.style.display === 'none' ? 'block' : 'none';
        adminToggle.querySelector('.toggle-icon').textContent = 
            adminForm.style.display === 'none' ? '▼' : '▲';
    });
    
    // Login (if admin elements exist)
    if (loginBtn) loginBtn.addEventListener('click', validateCredentials);
    if (adminPassword) adminPassword.addEventListener('keypress', (e) => {
        if (e.key === 'Enter') validateCredentials();
    });
    
    // Add config
    addConfigBtn.addEventListener('click', () => {
        openAddModal();
    });
    
    // Modal controls
    modalClose.addEventListener('click', closeModal);
    cancelBtn.addEventListener('click', closeModal);
    saveBtn.addEventListener('click', saveConfig);
    validateBtn.addEventListener('click', validateConfig);
    
    // Delete modal
    deleteModalClose.addEventListener('click', closeDeleteModal);
    deleteCancelBtn.addEventListener('click', closeDeleteModal);
    deleteConfirmBtn.addEventListener('click', confirmDelete);
    
    // Output modal
    outputModalClose.addEventListener('click', closeOutputModal);
    outputCloseBtn.addEventListener('click', closeOutputModal);
    
    // Files section
    closeFilesBtn.addEventListener('click', () => {
        filesSection.style.display = 'none';
        selectedConfigId = null;
        currentFiles = [];
        currentFilter = 'ALL';
    });
    
    // File filter tabs
    document.querySelectorAll('.filter-tab').forEach(tab => {
        tab.addEventListener('click', () => setFileFilter(tab.dataset.filter));
    });
    
    // File status cards (also work as filters)
    document.querySelectorAll('.file-status-card').forEach(card => {
        card.addEventListener('click', () => setFileFilter(card.dataset.filter));
    });
    
    // Command preview updates
    [serverNameInput, dbTypeInput, dbFolderInput, dumpFolderInput, javaPathInput, adminUserInput, adminPasswordInput]
        .filter(input => input !== null)
        .forEach(input => {
            input.addEventListener('input', updateCommandPreview);
            input.addEventListener('change', updateCommandPreview);
        });
    
    // Theme toggle
    themeToggle.addEventListener('click', toggleTheme);
    
    // Close modals on outside click
    window.addEventListener('click', (e) => {
        if (e.target === configModal) closeModal();
        if (e.target === deleteModal) closeDeleteModal();
        if (e.target === outputModal) closeOutputModal();
    });
}

// API Functions
async function loadConfigs() {
    try {
        const response = await fetch('/dump/configs');
        const data = await response.json();
        configs = data.configs || [];
        renderConfigs();
    } catch (error) {
        console.error('Error loading configs:', error);
    }
}

async function loadStatus() {
    try {
        const response = await fetch('/dump/status');
        const data = await response.json();
        
        watcherStatus.textContent = data.watcherRunning ? 'Running' : 'Stopped';
        watcherStatus.style.color = data.watcherRunning ? 'var(--success-color)' : 'var(--danger-color)';
        configCount.textContent = `${data.enabledConfigCount}/${data.configCount}`;
        pendingCount.textContent = data.totalPending;
        processingCount.textContent = data.currentlyProcessing;
        completedCount.textContent = data.totalCompleted;
        failedCount.textContent = data.totalFailed;
    } catch (error) {
        console.error('Error loading status:', error);
    }
}

async function loadFilesForConfig(configId) {
    try {
        const response = await fetch(`/dump/configs/${configId}/files`);
        const data = await response.json();
        selectedConfigName.textContent = data.serverName;
        currentFiles = data.files || [];
        currentFilter = 'ALL';
        updateFileStatusCounts();
        renderFiles(filterFiles(currentFiles, currentFilter));
        updateFilterTabs();
        filesSection.style.display = 'block';
        selectedConfigId = configId;
    } catch (error) {
        console.error('Error loading files:', error);
    }
}

function updateFileStatusCounts() {
    const counts = {
        PENDING: 0,
        PROCESSING: 0,
        COMPLETED: 0,
        FAILED: 0
    };
    
    currentFiles.forEach(file => {
        if (counts.hasOwnProperty(file.status)) {
            counts[file.status]++;
        }
    });
    
    document.getElementById('filePendingCount').textContent = counts.PENDING;
    document.getElementById('fileProcessingCount').textContent = counts.PROCESSING;
    document.getElementById('fileCompletedCount').textContent = counts.COMPLETED;
    document.getElementById('fileFailedCount').textContent = counts.FAILED;
}

function filterFiles(files, status) {
    if (status === 'ALL') return files;
    return files.filter(f => f.status === status);
}

function updateFilterTabs() {
    document.querySelectorAll('.filter-tab').forEach(tab => {
        tab.classList.toggle('active', tab.dataset.filter === currentFilter);
    });
    document.querySelectorAll('.file-status-card').forEach(card => {
        card.classList.toggle('active', card.dataset.filter === currentFilter);
    });
}

function setFileFilter(status) {
    currentFilter = status;
    updateFilterTabs();
    renderFiles(filterFiles(currentFiles, currentFilter));
}

async function validateCredentials() {
    const username = adminUsername.value;
    const password = adminPassword.value;
    
    if (!username || !password) {
        showAdminStatus('Please enter username and password', 'error');
        return;
    }
    
    try {
        const response = await fetch('/infra/auth/validate', {
            headers: {
                'X-Admin-Username': username,
                'X-Admin-Password': password
            }
        });
        
        const data = await response.json();
        
        if (data.valid) {
            isAdmin = true;
            adminCredentials = { username, password };
            sessionStorage.setItem('adminCredentials', JSON.stringify(adminCredentials));
            showAdminStatus('Logged in successfully', 'success');
            showAdminColumns();
            loadConfigs();
        } else {
            isAdmin = false;
            showAdminStatus(data.error || 'Invalid credentials', 'error');
        }
    } catch (error) {
        console.error('Error validating credentials:', error);
        showAdminStatus('Error validating credentials', 'error');
    }
}

async function saveConfig() {
    const config = {
        serverName: serverNameInput.value,
        dbType: dbTypeInput.value,
        dbFolder: dbFolderInput.value,
        dumpFolder: dumpFolderInput.value,
        javaPath: javaPathInput.value,
        thresholdMinutes: parseInt(thresholdMinutesInput.value) || 1,
        adminUser: adminUserInput.value || null,
        enabled: enabledInput.checked
    };
    
    // Only include password if provided (allows keeping existing password on edit)
    if (adminPasswordInput.value) {
        config.adminPassword = adminPasswordInput.value;
    }
    
    const id = configIdInput.value;
    const url = id ? `/dump/configs/${id}` : '/dump/configs';
    const method = id ? 'PUT' : 'POST';
    
    try {
        const response = await fetch(url, {
            method,
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(config)
        });
        
        const data = await response.json();
        
        if (response.ok) {
            closeModal();
            loadConfigs();
            loadStatus();
        } else {
            alert(data.error || 'Failed to save configuration');
        }
    } catch (error) {
        console.error('Error saving config:', error);
        alert('Error saving configuration');
    }
}

async function deleteConfig(id) {
    deleteConfigId = id;
    deleteModal.style.display = 'flex';
}

async function confirmDelete() {
    if (!deleteConfigId) return;
    
    try {
        const response = await fetch(`/dump/configs/${deleteConfigId}`, {
            method: 'DELETE'
        });
        
        if (response.ok) {
            closeDeleteModal();
            loadConfigs();
            loadStatus();
            if (selectedConfigId === deleteConfigId) {
                filesSection.style.display = 'none';
                selectedConfigId = null;
            }
        } else {
            const data = await response.json();
            alert(data.error || 'Failed to delete configuration');
        }
    } catch (error) {
        console.error('Error deleting config:', error);
        alert('Error deleting configuration');
    }
}

async function triggerProcessing(configId) {
    try {
        const response = await fetch(`/dump/configs/${configId}/run`, {
            method: 'POST'
        });
        
        const data = await response.json();
        
        if (response.ok) {
            alert(data.message || 'Processing triggered');
            setTimeout(() => {
                loadConfigs();
                loadStatus();
                if (selectedConfigId === configId) {
                    loadFilesForConfig(configId);
                }
            }, 2000);
        } else {
            alert(data.error || 'Failed to trigger processing');
        }
    } catch (error) {
        console.error('Error triggering processing:', error);
        alert('Error triggering processing');
    }
}

async function validateConfig() {
    const config = {
        serverName: serverNameInput.value,
        dbType: dbTypeInput.value,
        dbFolder: dbFolderInput.value,
        dumpFolder: dumpFolderInput.value,
        javaPath: javaPathInput.value,
        thresholdMinutes: parseInt(thresholdMinutesInput.value) || 1,
        adminUser: adminUserInput.value || null
    };
    
    // Include password if provided
    if (adminPasswordInput.value) {
        config.adminPassword = adminPasswordInput.value;
    }
    
    try {
        const response = await fetch('/dump/validate', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(config)
        });
        
        const data = await response.json();
        
        validationResult.style.display = 'block';
        if (data.valid) {
            validationResult.className = 'validation-result success';
            validationResult.textContent = '✓ Configuration is valid';
        } else {
            validationResult.className = 'validation-result error';
            validationResult.textContent = '✗ ' + (data.error || 'Invalid configuration');
        }
    } catch (error) {
        console.error('Error validating config:', error);
        validationResult.style.display = 'block';
        validationResult.className = 'validation-result error';
        validationResult.textContent = '✗ Error validating configuration';
    }
}

// Render Functions
function renderConfigs() {
    if (!configs || configs.length === 0) {
        configTableBody.innerHTML = `
            <tr class="empty-row">
                <td colspan="8">
                    <div class="empty-state">
                        <div class="empty-icon">📦</div>
                        <p>No dump processing configurations yet</p>
                    </div>
                </td>
            </tr>
        `;
        return;
    }
    
    configTableBody.innerHTML = configs.map((config, index) => `
        <tr class="clickable-row" onclick="loadFilesForConfig(${config.id})">
            <td>${index + 1}</td>
            <td>${escapeHtml(config.serverName)}</td>
            <td>${escapeHtml(config.dbType)}</td>
            <td class="cell-truncate" title="${escapeHtml(config.dumpFolder)}">${escapeHtml(config.dumpFolder)}</td>
            <td>${config.thresholdMinutes} min</td>
            <td>
                <span class="status-badge ${config.enabled ? 'enabled' : 'disabled'}">
                    ${config.enabled ? 'Enabled' : 'Disabled'}
                </span>
                ${config.stats ? `
                    <br><small style="color: var(--text-muted)">
                        P:${config.stats.pending} / C:${config.stats.completed} / F:${config.stats.failed}
                    </small>
                ` : ''}
            </td>
            <td>
                ${config.lastRunTime ? `
                    <span class="last-run-info ${config.lastRunStatus === 'SUCCESS' ? 'success' : 'failed'}">
                        ${formatDate(config.lastRunTime)}<br>
                        ${config.lastRunStatus || '--'}
                    </span>
                ` : '<span class="last-run-info">Never</span>'}
            </td>
            <td class="admin-only" style="${isAdmin ? '' : 'display: none;'}" onclick="event.stopPropagation()">
                <div class="action-buttons">
                    <button class="btn btn-icon-sm btn-run" onclick="triggerProcessing(${config.id})" title="Run Now">▶</button>
                    <button class="btn btn-icon-sm btn-secondary" onclick="editConfig(${config.id})" title="Edit">✏️</button>
                    <button class="btn btn-icon-sm btn-danger" onclick="deleteConfig(${config.id})" title="Delete">🗑️</button>
                </div>
            </td>
        </tr>
    `).join('');
}

function renderFiles(files) {
    if (!files || files.length === 0) {
        const emptyMessage = currentFilter === 'ALL' 
            ? 'No files tracked for this configuration'
            : `No ${currentFilter.toLowerCase()} files`;
        filesTableBody.innerHTML = `
            <tr>
                <td colspan="8" style="text-align: center; padding: 2rem;">
                    ${emptyMessage}
                </td>
            </tr>
        `;
        return;
    }
    
    // Store file outputs in a map for safe retrieval (avoids HTML attribute injection)
    window._fileOutputs = {};
    files.forEach(file => {
        if (file.processOutput) {
            window._fileOutputs[file.id] = file.processOutput;
        }
    });
    
    filesTableBody.innerHTML = files.map(file => {
        // Calculate process duration if available
        // Note: processStartTime and processEndTime are ISO timestamp strings from the backend
        let processTimeHtml = '--';
        if (file.processStartTime && file.processEndTime) {
            const startTime = new Date(file.processStartTime).getTime();
            const endTime = new Date(file.processEndTime).getTime();
            const durationMs = endTime - startTime;
            const durationSec = Math.round(durationMs / 1000);
            const statusClass = file.status === 'COMPLETED' ? 'success' : (file.status === 'FAILED' ? 'failed' : '');
            processTimeHtml = `<span class="process-time ${statusClass}">${formatDuration(durationSec)}</span>`;
        } else if (file.processStartTime && file.status === 'PROCESSING') {
            processTimeHtml = '<span class="process-time">In progress...</span>';
        }
        
        // Status icon for better visibility
        const statusIcons = {
            'PENDING': '⏳',
            'PROCESSING': '🔄',
            'COMPLETED': '✅',
            'FAILED': '❌'
        };
        const statusIcon = statusIcons[file.status] || '';
        
        // Retry info with warning if retries > 0
        let retryHtml = file.retryCount.toString();
        if (file.retryCount > 0 && file.status === 'FAILED') {
            retryHtml = `<span style="color: var(--danger-color)">${file.retryCount} (max reached)</span>`;
        } else if (file.retryCount > 0) {
            retryHtml = `<span style="color: var(--warning-color, #f39c12)">${file.retryCount}</span>`;
        }
        
        return `
        <tr class="${file.status === 'FAILED' ? 'row-failed' : ''}">
            <td class="cell-truncate" title="${escapeHtml(file.fileName)}">${escapeHtml(file.fileName)}</td>
            <td>${formatFileSize(file.fileSize)}</td>
            <td><span title="${formatTimestamp(file.firstSeenTime)}">${formatTimeAgo(file.firstSeenTime)}</span></td>
            <td>${file.ageMinutes} min</td>
            <td>
                <span class="status-badge ${file.status.toLowerCase()}">
                    ${statusIcon} ${file.status}
                </span>
            </td>
            <td>${retryHtml}</td>
            <td>${processTimeHtml}</td>
            <td>
                ${file.processOutput ? `
                    <button class="btn btn-sm btn-secondary" data-file-id="${file.id}" onclick="showOutputById(this.dataset.fileId)">
                        View
                    </button>
                ` : '--'}
            </td>
        </tr>
    `}).join('');
}

// Modal Functions
function openAddModal() {
    modalTitle.textContent = 'Add Dump Processing Configuration';
    configForm.reset();
    configIdInput.value = '';
    enabledInput.checked = true;
    thresholdMinutesInput.value = '1';
    if (adminPasswordInput) adminPasswordInput.value = '';
    if (passwordHint) passwordHint.style.display = 'none';
    validationResult.style.display = 'none';
    updateCommandPreview();
    configModal.style.display = 'flex';
}

function editConfig(id) {
    const config = configs.find(c => c.id === id);
    if (!config) return;
    
    modalTitle.textContent = 'Edit Dump Processing Configuration';
    configIdInput.value = config.id;
    serverNameInput.value = config.serverName || '';
    dbTypeInput.value = config.dbType || '';
    dbFolderInput.value = config.dbFolder || '';
    dumpFolderInput.value = config.dumpFolder || '';
    javaPathInput.value = config.javaPath || '';
    thresholdMinutesInput.value = config.thresholdMinutes || 1;
    if (adminUserInput) adminUserInput.value = config.adminUser || '';
    if (adminPasswordInput) adminPasswordInput.value = ''; // Don't populate password for security
    // Show hint if password is already set
    if (passwordHint) passwordHint.style.display = config.hasPassword ? 'block' : 'none';
    enabledInput.checked = config.enabled !== false;
    validationResult.style.display = 'none';
    updateCommandPreview();
    configModal.style.display = 'flex';
}

function closeModal() {
    configModal.style.display = 'none';
    configForm.reset();
}

function closeDeleteModal() {
    deleteModal.style.display = 'none';
    deleteConfigId = null;
}

function closeOutputModal() {
    outputModal.style.display = 'none';
}

function showOutput(output) {
    outputContent.textContent = output || 'No output available';
    outputModal.style.display = 'flex';
}

function showOutputById(fileId) {
    const output = window._fileOutputs ? window._fileOutputs[fileId] : null;
    showOutput(output);
}

function updateCommandPreview() {
    const dbType = dbTypeInput ? dbTypeInput.value || '<DB_TYPE>' : '<DB_TYPE>';
    const javaPath = javaPathInput ? javaPathInput.value || '<JAVA_PATH>' : '<JAVA_PATH>';
    const dumpFolder = dumpFolderInput ? dumpFolderInput.value || '<DUMP_FOLDER>' : '<DUMP_FOLDER>';
    const dbFolder = dbFolderInput ? dbFolderInput.value || '<DB_FOLDER>' : '<DB_FOLDER>';
    const adminUser = adminUserInput ? adminUserInput.value : '';
    const hasPassword = (adminPasswordInput && adminPasswordInput.value) || 
                        (passwordHint && passwordHint.style.display !== 'none');
    
    // Quote paths to handle spaces - using single quotes with escaped single quotes within
    const quotePath = (p) => "'" + p.replace(/'/g, "'\\''") + "'";
    
    // The script command (without cd)
    const scriptCommand = `./ExtractMDB.do.sh ${dbType} ${quotePath(javaPath)} ${quotePath(dumpFolder)}`;
    
    let command;
    const suTarget = adminUser ? `su - ${adminUser}` : 'su';
    
    if (hasPassword) {
        // With password: use here-document to send password then command
        command = `cd ${quotePath(dbFolder)} && ${suTarget} << 'SUEOF'\n****\n${scriptCommand}\nSUEOF`;
    } else {
        // No password: use here-document with just command
        command = `cd ${quotePath(dbFolder)} && ${suTarget} << 'SUEOF'\n${scriptCommand}\nSUEOF`;
    }
    
    if (commandPreviewText) commandPreviewText.textContent = command;
}

// Helper Functions
function showAdminStatus(message, type) {
    adminStatus.textContent = message;
    adminStatus.className = 'admin-status ' + type;
    adminStatus.style.display = 'block';
}

function showAdminRequired() {
    adminForm.style.display = 'block';
    adminToggle.querySelector('.toggle-icon').textContent = '▲';
    showAdminStatus('Admin login required for this action', 'error');
}

function showAdminColumns() {
    document.querySelectorAll('.admin-only').forEach(el => {
        el.style.display = '';
    });
}

function escapeHtml(str) {
    if (!str) return '';
    return str
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
}

function formatDate(dateStr) {
    if (!dateStr) return '--';
    try {
        const date = new Date(dateStr);
        return date.toLocaleString();
    } catch {
        return dateStr;
    }
}

function formatFileSize(bytes) {
    if (!bytes || bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}

function formatDuration(seconds) {
    if (seconds < 60) return `${seconds}s`;
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    if (mins < 60) return `${mins}m ${secs}s`;
    const hours = Math.floor(mins / 60);
    const remMins = mins % 60;
    return `${hours}h ${remMins}m`;
}

function formatTimestamp(timestamp) {
    if (!timestamp) return '--';
    try {
        const date = new Date(timestamp);
        return date.toLocaleString();
    } catch {
        return '--';
    }
}

function formatTimeAgo(timestamp) {
    if (!timestamp) return '--';
    try {
        const now = Date.now();
        const diff = now - timestamp;
        const mins = Math.floor(diff / 60000);
        
        if (mins < 1) return 'Just now';
        if (mins < 60) return `${mins}m ago`;
        
        const hours = Math.floor(mins / 60);
        if (hours < 24) return `${hours}h ago`;
        
        const days = Math.floor(hours / 24);
        return `${days}d ago`;
    } catch {
        return '--';
    }
}

// Theme
function loadTheme() {
    const theme = localStorage.getItem('theme') || 'light';
    document.documentElement.setAttribute('data-theme', theme);
    themeToggle.textContent = theme === 'dark' ? '☀️' : '🌙';
}

function toggleTheme() {
    const currentTheme = document.documentElement.getAttribute('data-theme');
    const newTheme = currentTheme === 'dark' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', newTheme);
    localStorage.setItem('theme', newTheme);
    themeToggle.textContent = newTheme === 'dark' ? '☀️' : '🌙';
}
