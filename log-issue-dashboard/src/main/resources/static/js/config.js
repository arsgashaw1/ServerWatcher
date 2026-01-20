/**
 * Configuration Management - Frontend Application
 * Allows adding/removing watch directories and file patterns from the UI.
 */

class ConfigManager {
    constructor() {
        this.directories = [];
        this.patterns = [];
        this.encodings = [];
        this.settings = {};
        this.requiresAuth = false;
        this.authCredentials = null;
        this.currentWizardStep = 1;
        this.pendingAction = null;
        this.browserPath = null;
        
        this.init();
    }
    
    init() {
        this.setupTheme();
        this.setupEventListeners();
        this.setupTabs();
        this.loadInitialData();
    }
    
    // Theme handling
    setupTheme() {
        const savedTheme = localStorage.getItem('theme') || 'light';
        document.documentElement.setAttribute('data-theme', savedTheme);
        this.updateThemeButton(savedTheme);
    }
    
    toggleTheme() {
        const current = document.documentElement.getAttribute('data-theme');
        const next = current === 'dark' ? 'light' : 'dark';
        document.documentElement.setAttribute('data-theme', next);
        localStorage.setItem('theme', next);
        this.updateThemeButton(next);
    }
    
    updateThemeButton(theme) {
        const btn = document.getElementById('themeToggle');
        btn.textContent = theme === 'dark' ? '☀️' : '🌙';
    }
    
    // Event listeners
    setupEventListeners() {
        // Theme toggle
        document.getElementById('themeToggle').addEventListener('click', () => this.toggleTheme());
        
        // Add directory button
        document.getElementById('addDirectoryBtn').addEventListener('click', () => this.showAddDirectoryModal());
        
        // Add pattern button
        document.getElementById('addPatternBtn').addEventListener('click', () => this.showAddPatternModal());
        
        // Directory modal
        document.getElementById('addDirModalClose').addEventListener('click', () => this.closeAddDirectoryModal());
        document.getElementById('wizardCancelBtn').addEventListener('click', () => this.closeAddDirectoryModal());
        document.getElementById('wizardPrevBtn').addEventListener('click', () => this.wizardPrev());
        document.getElementById('wizardNextBtn').addEventListener('click', () => this.wizardNext());
        document.getElementById('browseBtn').addEventListener('click', () => this.toggleBrowser());
        document.getElementById('browserUp').addEventListener('click', () => this.browserGoUp());
        document.getElementById('pathInput').addEventListener('input', (e) => this.validatePath(e.target.value));
        
        // Pattern modal
        document.getElementById('addPatternModalClose').addEventListener('click', () => this.closeAddPatternModal());
        document.getElementById('patternCancelBtn').addEventListener('click', () => this.closeAddPatternModal());
        document.getElementById('patternSaveBtn').addEventListener('click', () => this.savePattern());
        document.getElementById('patternInput').addEventListener('keypress', (e) => {
            if (e.key === 'Enter') this.savePattern();
        });
        
        // Auth modal
        document.getElementById('authModalClose').addEventListener('click', () => this.closeAuthModal());
        document.getElementById('authCancelBtn').addEventListener('click', () => this.closeAuthModal());
        document.getElementById('authLoginBtn').addEventListener('click', () => this.authenticate());
        document.getElementById('authPassword').addEventListener('keypress', (e) => {
            if (e.key === 'Enter') this.authenticate();
        });
        
        // Confirm modal
        document.getElementById('confirmModalClose').addEventListener('click', () => this.closeConfirmModal());
        document.getElementById('confirmCancelBtn').addEventListener('click', () => this.closeConfirmModal());
        document.getElementById('confirmDeleteBtn').addEventListener('click', () => this.confirmDelete());
        
        // Modal backdrop clicks
        document.getElementById('addDirectoryModal').addEventListener('click', (e) => {
            if (e.target.id === 'addDirectoryModal') this.closeAddDirectoryModal();
        });
        document.getElementById('addPatternModal').addEventListener('click', (e) => {
            if (e.target.id === 'addPatternModal') this.closeAddPatternModal();
        });
        document.getElementById('authModal').addEventListener('click', (e) => {
            if (e.target.id === 'authModal') this.closeAuthModal();
        });
        document.getElementById('confirmModal').addEventListener('click', (e) => {
            if (e.target.id === 'confirmModal') this.closeConfirmModal();
        });
        
        // Keyboard shortcuts
        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') {
                this.closeAddDirectoryModal();
                this.closeAddPatternModal();
                this.closeAuthModal();
                this.closeConfirmModal();
            }
        });
    }
    
    setupTabs() {
        document.querySelectorAll('.config-tab').forEach(tab => {
            tab.addEventListener('click', () => {
                document.querySelectorAll('.config-tab').forEach(t => t.classList.remove('active'));
                document.querySelectorAll('.config-section').forEach(s => s.classList.remove('active'));
                
                tab.classList.add('active');
                document.getElementById('tab-' + tab.dataset.tab).classList.add('active');
            });
        });
    }
    
    // Data loading
    async loadInitialData() {
        try {
            await Promise.all([
                this.loadStatus(),
                this.loadDirectories(),
                this.loadPatterns(),
                this.loadEncodings(),
                this.loadSettings()
            ]);
        } catch (error) {
            console.error('Error loading initial data:', error);
        }
    }
    
    async loadStatus() {
        try {
            const response = await fetch('/config/status');
            const data = await response.json();
            
            document.getElementById('watchDirCount').textContent = data.totalWatchDirectories || 0;
            document.getElementById('trackedFileCount').textContent = data.totalTrackedFiles || 0;
            document.getElementById('pollingInterval').textContent = 
                (data.pollingInterval || 2) + 's';
            document.getElementById('configPath').textContent = data.configPath || '-';
            
            this.requiresAuth = data.requiresAuth || false;
            this.updateAuthStatus();
        } catch (error) {
            console.error('Error loading status:', error);
        }
    }
    
    async loadDirectories() {
        try {
            const response = await fetch('/config/watch-directories');
            const data = await response.json();
            
            this.directories = data.directories || [];
            this.requiresAuth = data.requiresAuth || false;
            this.renderDirectories();
            this.updateAuthStatus();
        } catch (error) {
            console.error('Error loading directories:', error);
        }
    }
    
    async loadPatterns() {
        try {
            const response = await fetch('/config/file-patterns');
            const data = await response.json();
            
            this.patterns = data.patterns || [];
            this.renderPatterns();
        } catch (error) {
            console.error('Error loading patterns:', error);
        }
    }
    
    async loadEncodings() {
        try {
            const response = await fetch('/config/encodings');
            this.encodings = await response.json();
            this.populateEncodingSelect();
        } catch (error) {
            console.error('Error loading encodings:', error);
        }
    }
    
    async loadSettings() {
        try {
            const response = await fetch('/config/settings');
            this.settings = await response.json();
            this.renderSettings();
        } catch (error) {
            console.error('Error loading settings:', error);
        }
    }
    
    // Rendering
    renderDirectories() {
        const list = document.getElementById('directoriesList');
        
        if (this.directories.length === 0) {
            list.innerHTML = `
                <div class="empty-state">
                    <div class="empty-icon">📁</div>
                    <p>No watch directories configured</p>
                    <p class="empty-hint">Click "Add Watch Directory" to start monitoring log files</p>
                </div>
            `;
            return;
        }
        
        list.innerHTML = this.directories.map(dir => `
            <div class="directory-card ${dir.exists ? '' : 'warning'}">
                <div class="directory-header">
                    <div class="directory-icon">${dir.exists ? '📁' : '⚠️'}</div>
                    <div class="directory-info">
                        <div class="directory-name">${this.escapeHtml(dir.serverName || 'Unnamed')}</div>
                        <div class="directory-path">${this.escapeHtml(dir.path)}</div>
                    </div>
                    <div class="directory-actions">
                        <button class="btn btn-sm btn-icon btn-danger" 
                                onclick="configManager.confirmRemoveDirectory('${this.escapeHtml(dir.serverName)}')"
                                title="Remove">
                            🗑️
                        </button>
                    </div>
                </div>
                <div class="directory-details">
                    <div class="detail-item">
                        <span class="detail-label">Status:</span>
                        <span class="detail-value ${dir.exists ? 'status-ok' : 'status-error'}">
                            ${dir.exists ? '✓ Active' : '✗ Path not found'}
                        </span>
                    </div>
                    <div class="detail-item">
                        <span class="detail-label">Encoding:</span>
                        <span class="detail-value">${this.escapeHtml(dir.encoding || 'UTF-8')}</span>
                    </div>
                    <div class="detail-item">
                        <span class="detail-label">Files tracked:</span>
                        <span class="detail-value">${dir.trackedFileCount || 0}</span>
                    </div>
                    ${dir.description ? `
                        <div class="detail-item full-width">
                            <span class="detail-label">Description:</span>
                            <span class="detail-value">${this.escapeHtml(dir.description)}</span>
                        </div>
                    ` : ''}
                </div>
            </div>
        `).join('');
    }
    
    renderPatterns() {
        const list = document.getElementById('patternsList');
        
        if (this.patterns.length === 0) {
            list.innerHTML = `
                <div class="empty-state">
                    <div class="empty-icon">📝</div>
                    <p>No file patterns configured</p>
                </div>
            `;
            return;
        }
        
        list.innerHTML = `
            <div class="patterns-grid">
                ${this.patterns.map(pattern => `
                    <div class="pattern-item">
                        <span class="pattern-text">${this.escapeHtml(pattern)}</span>
                        <button class="btn btn-sm btn-icon btn-danger" 
                                onclick="configManager.confirmRemovePattern('${this.escapeHtml(pattern)}')"
                                title="Remove pattern"
                                ${this.patterns.length <= 1 ? 'disabled' : ''}>
                            🗑️
                        </button>
                    </div>
                `).join('')}
            </div>
            <p class="patterns-hint">
                These patterns determine which files are monitored in watch directories.
            </p>
        `;
    }
    
    renderSettings() {
        document.getElementById('settingPolling').textContent = 
            (this.settings.pollingIntervalSeconds || 2) + ' seconds';
        document.getElementById('settingMaxIssues').textContent = 
            this.settings.maxIssuesDisplayed || 500;
        document.getElementById('settingStorage').textContent = 
            (this.settings.storageType || 'memory').toUpperCase();
        document.getElementById('settingPort').textContent = 
            this.settings.webServerPort || 8080;
        document.getElementById('settingSound').textContent = 
            this.settings.enableSound ? 'Enabled' : 'Disabled';
        document.getElementById('settingTitle').textContent = 
            this.settings.windowTitle || 'Sentinel';
    }
    
    populateEncodingSelect() {
        const select = document.getElementById('encodingSelect');
        select.innerHTML = this.encodings.map(enc => 
            `<option value="${this.escapeHtml(enc.value)}" title="${this.escapeHtml(enc.description)}">
                ${this.escapeHtml(enc.label)}
            </option>`
        ).join('');
    }
    
    updateAuthStatus() {
        const statusEl = document.getElementById('authStatus');
        
        if (this.authCredentials) {
            statusEl.innerHTML = `
                <span class="auth-icon">🔓</span>
                <span class="auth-text">Authenticated</span>
            `;
            statusEl.classList.add('authenticated');
        } else if (this.requiresAuth) {
            statusEl.innerHTML = `
                <span class="auth-icon">🔒</span>
                <span class="auth-text">Login required for changes</span>
            `;
            statusEl.classList.remove('authenticated');
        } else {
            statusEl.innerHTML = `
                <span class="auth-icon">⚠️</span>
                <span class="auth-text">Auth not configured</span>
            `;
            statusEl.classList.remove('authenticated');
        }
    }
    
    // Add Directory Wizard
    showAddDirectoryModal() {
        this.currentWizardStep = 1;
        this.updateWizardStep();
        
        // Reset form
        document.getElementById('pathInput').value = '';
        document.getElementById('serverNameInput').value = '';
        document.getElementById('descriptionInput').value = '';
        document.getElementById('encodingSelect').value = 'UTF-8';
        document.getElementById('useIconvCheck').checked = false;
        document.getElementById('pathValidation').innerHTML = '';
        document.getElementById('directoryBrowser').style.display = 'none';
        
        document.getElementById('addDirectoryModal').classList.add('active');
    }
    
    closeAddDirectoryModal() {
        document.getElementById('addDirectoryModal').classList.remove('active');
    }
    
    updateWizardStep() {
        // Update step indicators
        document.querySelectorAll('.wizard-step').forEach(step => {
            const stepNum = parseInt(step.dataset.step);
            step.classList.remove('active', 'completed');
            if (stepNum < this.currentWizardStep) {
                step.classList.add('completed');
            } else if (stepNum === this.currentWizardStep) {
                step.classList.add('active');
            }
        });
        
        // Show/hide panels
        document.querySelectorAll('.wizard-panel').forEach((panel, index) => {
            panel.classList.toggle('active', index + 1 === this.currentWizardStep);
        });
        
        // Update buttons
        document.getElementById('wizardPrevBtn').style.display = 
            this.currentWizardStep > 1 ? 'inline-block' : 'none';
        
        const nextBtn = document.getElementById('wizardNextBtn');
        if (this.currentWizardStep === 3) {
            nextBtn.textContent = '✓ Add Directory';
            nextBtn.classList.add('btn-success');
        } else {
            nextBtn.textContent = 'Next →';
            nextBtn.classList.remove('btn-success');
        }
    }
    
    async wizardPrev() {
        if (this.currentWizardStep > 1) {
            this.currentWizardStep--;
            this.updateWizardStep();
        }
    }
    
    async wizardNext() {
        if (this.currentWizardStep === 1) {
            // Validate path
            const path = document.getElementById('pathInput').value.trim();
            if (!path) {
                this.showPathError('Please enter a path');
                return;
            }
            
            const validation = await this.validatePath(path);
            if (!validation || !validation.valid) {
                return;
            }
            
            // Auto-generate server name
            if (!document.getElementById('serverNameInput').value) {
                const pathParts = path.split(/[/\\]/);
                const dirName = pathParts[pathParts.length - 1] || 'SERVER';
                document.getElementById('serverNameInput').value = 
                    dirName.toUpperCase().replace(/[^A-Z0-9]/g, '-');
            }
            
            this.currentWizardStep = 2;
            this.updateWizardStep();
            
        } else if (this.currentWizardStep === 2) {
            // Validate server name
            const serverName = document.getElementById('serverNameInput').value.trim();
            if (!serverName) {
                alert('Please enter a server name');
                return;
            }
            
            // Load preview
            await this.loadPreview();
            
            this.currentWizardStep = 3;
            this.updateWizardStep();
            
        } else if (this.currentWizardStep === 3) {
            // Save directory
            await this.saveDirectory();
        }
    }
    
    async validatePath(path) {
        if (!path) {
            document.getElementById('pathValidation').innerHTML = '';
            return null;
        }
        
        try {
            const response = await fetch(`/config/validate?path=${encodeURIComponent(path)}`);
            const data = await response.json();
            
            const validationEl = document.getElementById('pathValidation');
            
            if (data.valid) {
                validationEl.innerHTML = `
                    <div class="validation-success">
                        ✓ Valid path - ${data.matchingFileCount || 0} matching files found
                    </div>
                `;
                if (data.warning) {
                    validationEl.innerHTML += `
                        <div class="validation-warning">⚠️ ${this.escapeHtml(data.warning)}</div>
                    `;
                }
            } else {
                validationEl.innerHTML = `
                    <div class="validation-error">✗ ${this.escapeHtml(data.error)}</div>
                `;
            }
            
            return data;
        } catch (error) {
            document.getElementById('pathValidation').innerHTML = `
                <div class="validation-error">✗ Error validating path</div>
            `;
            return null;
        }
    }
    
    showPathError(message) {
        document.getElementById('pathValidation').innerHTML = `
            <div class="validation-error">✗ ${this.escapeHtml(message)}</div>
        `;
    }
    
    async loadPreview() {
        const path = document.getElementById('pathInput').value.trim();
        const serverName = document.getElementById('serverNameInput').value.trim();
        const encoding = document.getElementById('encodingSelect').value;
        const description = document.getElementById('descriptionInput').value.trim();
        
        // Update summary
        document.getElementById('previewPath').textContent = path;
        document.getElementById('previewServerName').textContent = serverName;
        document.getElementById('previewEncoding').textContent = encoding;
        document.getElementById('previewDescription').textContent = description || '(none)';
        
        // Load files preview
        try {
            const response = await fetch(`/config/preview?path=${encodeURIComponent(path)}`);
            const data = await response.json();
            
            document.getElementById('previewFileCount').textContent = data.totalMatchingFiles || 0;
            
            const filesList = document.getElementById('filesPreviewList');
            if (data.files && data.files.length > 0) {
                filesList.innerHTML = data.files.map(f => `
                    <div class="file-preview-item">
                        <span class="file-name">${this.escapeHtml(f.name)}</span>
                        <span class="file-size">${this.formatBytes(f.size)}</span>
                    </div>
                `).join('');
                
                if (data.truncated) {
                    filesList.innerHTML += `
                        <div class="file-preview-more">
                            ... and ${data.totalMatchingFiles - data.files.length} more files
                        </div>
                    `;
                }
            } else {
                filesList.innerHTML = `
                    <div class="file-preview-empty">No matching files found</div>
                `;
            }
        } catch (error) {
            console.error('Error loading preview:', error);
        }
    }
    
    async saveDirectory() {
        const data = {
            path: document.getElementById('pathInput').value.trim(),
            serverName: document.getElementById('serverNameInput').value.trim(),
            description: document.getElementById('descriptionInput').value.trim() || null,
            encoding: document.getElementById('encodingSelect').value,
            useIconv: document.getElementById('useIconvCheck').checked
        };
        
        await this.saveDirectoryWithAuth(data);
    }
    
    async saveDirectoryWithAuth(data) {
        try {
            const response = await fetch('/config/watch-directories/add', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify(data)
            });
            
            const result = await response.json();
            
            if (result.success) {
                this.closeAddDirectoryModal();
                this.loadDirectories();
                this.loadStatus();
                this.showNotification('Watch directory added successfully', 'success');
            } else {
                alert('Error: ' + (result.error || 'Failed to add directory'));
            }
        } catch (error) {
            console.error('Error saving directory:', error);
            alert('Error saving directory: ' + error.message);
        }
    }
    
    // Directory Browser
    toggleBrowser() {
        const browser = document.getElementById('directoryBrowser');
        const isVisible = browser.style.display !== 'none';
        
        if (isVisible) {
            browser.style.display = 'none';
        } else {
            browser.style.display = 'block';
            const currentPath = document.getElementById('pathInput').value.trim();
            this.browsePath(currentPath || null);
        }
    }
    
    async browsePath(path) {
        try {
            const url = path 
                ? `/config/browse?path=${encodeURIComponent(path)}`
                : '/config/browse';
            
            const response = await fetch(url);
            const data = await response.json();
            
            if (data.error) {
                document.getElementById('browserContent').innerHTML = `
                    <div class="browser-error">${this.escapeHtml(data.error)}</div>
                `;
                return;
            }
            
            this.browserPath = data.currentPath;
            document.getElementById('browserPath').textContent = data.currentPath;
            document.getElementById('browserUp').disabled = !data.parentPath;
            
            const content = document.getElementById('browserContent');
            content.innerHTML = data.entries.map(entry => `
                <div class="browser-item ${entry.isDirectory ? 'directory' : 'file'}"
                     onclick="configManager.selectBrowserItem('${this.escapeJs(entry.path)}', ${entry.isDirectory})">
                    <span class="browser-item-icon">${entry.isDirectory ? '📁' : '📄'}</span>
                    <span class="browser-item-name">${this.escapeHtml(entry.name)}</span>
                    ${entry.isDirectory 
                        ? `<span class="browser-item-info">${entry.logFileCount >= 0 ? entry.logFileCount + ' log files' : ''}</span>`
                        : `<span class="browser-item-info">${entry.isLogFile ? '✓ log file' : ''}</span>`
                    }
                </div>
            `).join('');
            
        } catch (error) {
            console.error('Error browsing:', error);
            document.getElementById('browserContent').innerHTML = `
                <div class="browser-error">Error loading directory</div>
            `;
        }
    }
    
    browserGoUp() {
        if (this.browserPath) {
            const parts = this.browserPath.split(/[/\\]/);
            parts.pop();
            const parentPath = parts.join('/') || '/';
            this.browsePath(parentPath);
        }
    }
    
    selectBrowserItem(path, isDirectory) {
        if (isDirectory) {
            // Navigate into directory
            this.browsePath(path);
        }
        // Set the path input
        document.getElementById('pathInput').value = path;
        this.validatePath(path);
    }
    
    // Pattern Modal
    showAddPatternModal() {
        document.getElementById('patternInput').value = '';
        document.getElementById('addPatternModal').classList.add('active');
        document.getElementById('patternInput').focus();
    }
    
    closeAddPatternModal() {
        document.getElementById('addPatternModal').classList.remove('active');
    }
    
    async savePattern() {
        const pattern = document.getElementById('patternInput').value.trim();
        if (!pattern) {
            alert('Please enter a pattern');
            return;
        }
        
        await this.savePatternWithAuth(pattern);
    }
    
    async savePatternWithAuth(pattern) {
        try {
            const response = await fetch('/config/file-patterns/add', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({ pattern })
            });
            
            const result = await response.json();
            
            if (result.success) {
                this.closeAddPatternModal();
                this.loadPatterns();
                this.showNotification('File pattern added', 'success');
            } else {
                alert('Error: ' + (result.error || 'Failed to add pattern'));
            }
        } catch (error) {
            console.error('Error saving pattern:', error);
            alert('Error saving pattern: ' + error.message);
        }
    }
    
    // Delete confirmations
    confirmRemoveDirectory(serverName) {
        document.getElementById('confirmMessage').textContent = 
            `Are you sure you want to remove the watch directory "${serverName}"? ` +
            `This will stop monitoring files in that directory after restart.`;
        
        this.pendingDeleteAction = () => this.removeDirectory(serverName);
        document.getElementById('confirmModal').classList.add('active');
    }
    
    confirmRemovePattern(pattern) {
        document.getElementById('confirmMessage').textContent = 
            `Are you sure you want to remove the file pattern "${pattern}"?`;
        
        this.pendingDeleteAction = () => this.removePattern(pattern);
        document.getElementById('confirmModal').classList.add('active');
    }
    
    closeConfirmModal() {
        document.getElementById('confirmModal').classList.remove('active');
        this.pendingDeleteAction = null;
    }
    
    async confirmDelete() {
        if (this.pendingDeleteAction) {
            await this.pendingDeleteAction();
            this.closeConfirmModal();
        }
    }
    
    async removeDirectory(serverName) {
        try {
            const response = await fetch(
                `/config/watch-directories/${encodeURIComponent(serverName)}`,
                { method: 'DELETE' }
            );
            
            const result = await response.json();
            
            if (result.success) {
                this.loadDirectories();
                this.loadStatus();
                this.showNotification('Watch directory removed', 'success');
            } else {
                alert('Error: ' + (result.error || 'Failed to remove directory'));
            }
        } catch (error) {
            console.error('Error removing directory:', error);
            alert('Error removing directory: ' + error.message);
        }
    }
    
    async removePattern(pattern) {
        try {
            const response = await fetch(
                `/config/file-patterns/${encodeURIComponent(pattern)}`,
                { method: 'DELETE' }
            );
            
            const result = await response.json();
            
            if (result.success) {
                this.loadPatterns();
                this.showNotification('File pattern removed', 'success');
            } else {
                alert('Error: ' + (result.error || 'Failed to remove pattern'));
            }
        } catch (error) {
            console.error('Error removing pattern:', error);
            alert('Error removing pattern: ' + error.message);
        }
    }
    
    // Authentication
    showAuthModal() {
        document.getElementById('authUsername').value = '';
        document.getElementById('authPassword').value = '';
        document.getElementById('authError').style.display = 'none';
        document.getElementById('authModal').classList.add('active');
        document.getElementById('authUsername').focus();
    }
    
    closeAuthModal() {
        document.getElementById('authModal').classList.remove('active');
        this.pendingAction = null;
    }
    
    async authenticate() {
        const username = document.getElementById('authUsername').value.trim();
        const password = document.getElementById('authPassword').value;
        
        if (!username || !password) {
            document.getElementById('authError').textContent = 'Please enter username and password';
            document.getElementById('authError').style.display = 'block';
            return;
        }
        
        // Test authentication with a simple request
        try {
            const response = await fetch('/config/status', {
                headers: {
                    'Authorization': 'Basic ' + btoa(username + ':' + password)
                }
            });
            
            if (response.ok) {
                this.authCredentials = { username, password };
                this.updateAuthStatus();
                this.closeAuthModal();
                
                // Execute pending action if any
                if (this.pendingAction) {
                    const action = this.pendingAction;
                    this.pendingAction = null;
                    await action();
                }
            } else {
                document.getElementById('authError').textContent = 'Invalid credentials';
                document.getElementById('authError').style.display = 'block';
            }
        } catch (error) {
            document.getElementById('authError').textContent = 'Authentication failed';
            document.getElementById('authError').style.display = 'block';
        }
    }
    
    // Notifications
    showNotification(message, type = 'info') {
        // Create notification element
        const notification = document.createElement('div');
        notification.className = `notification notification-${type}`;
        notification.innerHTML = `
            <span class="notification-icon">${type === 'success' ? '✓' : 'ℹ'}</span>
            <span class="notification-message">${this.escapeHtml(message)}</span>
        `;
        
        document.body.appendChild(notification);
        
        // Animate in
        setTimeout(() => notification.classList.add('show'), 10);
        
        // Remove after 3 seconds
        setTimeout(() => {
            notification.classList.remove('show');
            setTimeout(() => notification.remove(), 300);
        }, 3000);
    }
    
    // Utilities
    escapeHtml(text) {
        if (!text) return '';
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }
    
    escapeJs(text) {
        if (!text) return '';
        return text.replace(/'/g, "\\'").replace(/"/g, '\\"');
    }
    
    formatBytes(bytes) {
        if (bytes === 0) return '0 B';
        const k = 1024;
        const sizes = ['B', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
    }
}

// Initialize when DOM is ready
document.addEventListener('DOMContentLoaded', () => {
    window.configManager = new ConfigManager();
});
