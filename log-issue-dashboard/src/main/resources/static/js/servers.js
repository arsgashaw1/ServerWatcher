/**
 * Server Details Management - Frontend Application
 */

class ServerManager {
    constructor() {
        this.servers = [];
        this.isAdmin = false;
        this.adminCredentials = null;
        this.editingServerId = null;
        this.deletingServerId = null;
        
        this.init();
    }
    
    init() {
        this.setupTheme();
        this.setupEventListeners();
        this.loadServers();
        this.checkStoredCredentials();
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
    
    // Event Listeners
    setupEventListeners() {
        // Theme toggle
        document.getElementById('themeToggle').addEventListener('click', () => this.toggleTheme());
        
        // Admin toggle
        document.getElementById('adminToggle').addEventListener('click', () => this.toggleAdminForm());
        document.getElementById('loginBtn').addEventListener('click', () => this.login());
        
        // Add server button
        document.getElementById('addServerBtn').addEventListener('click', () => this.showAddModal());
        
        // Modal buttons
        document.getElementById('modalClose').addEventListener('click', () => this.closeModal());
        document.getElementById('cancelBtn').addEventListener('click', () => this.closeModal());
        document.getElementById('saveBtn').addEventListener('click', () => this.saveServer());
        
        // Delete modal
        document.getElementById('deleteModalClose').addEventListener('click', () => this.closeDeleteModal());
        document.getElementById('deleteCancelBtn').addEventListener('click', () => this.closeDeleteModal());
        document.getElementById('deleteConfirmBtn').addEventListener('click', () => this.confirmDelete());
        
        // Close modals on outside click
        document.getElementById('serverModal').addEventListener('click', (e) => {
            if (e.target.id === 'serverModal') this.closeModal();
        });
        document.getElementById('deleteModal').addEventListener('click', (e) => {
            if (e.target.id === 'deleteModal') this.closeDeleteModal();
        });
        
        // Keyboard shortcuts
        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') {
                this.closeModal();
                this.closeDeleteModal();
            }
        });
        
        // Enter key in password field
        document.getElementById('adminPassword').addEventListener('keypress', (e) => {
            if (e.key === 'Enter') this.login();
        });
    }
    
    // Admin functionality
    toggleAdminForm() {
        const form = document.getElementById('adminForm');
        const toggle = document.getElementById('adminToggle');
        const icon = toggle.querySelector('.toggle-icon');
        
        if (form.style.display === 'none') {
            form.style.display = 'block';
            icon.textContent = '▲';
        } else {
            form.style.display = 'none';
            icon.textContent = '▼';
        }
    }
    
    checkStoredCredentials() {
        const stored = sessionStorage.getItem('adminCredentials');
        if (stored) {
            this.adminCredentials = JSON.parse(stored);
            this.validateStoredCredentials();
        }
    }
    
    async validateStoredCredentials() {
        // Try a simple validation by making a test request
        try {
            const response = await fetch('/infra/servers', {
                method: 'GET'
            });
            if (response.ok) {
                this.isAdmin = true;
                this.updateAdminUI();
            }
        } catch (error) {
            this.isAdmin = false;
            sessionStorage.removeItem('adminCredentials');
            this.adminCredentials = null;
        }
    }
    
    async login() {
        const username = document.getElementById('adminUsername').value.trim();
        const password = document.getElementById('adminPassword').value;
        
        if (!username || !password) {
            this.showAdminStatus('Please enter both username and password', 'error');
            return;
        }
        
        // Test credentials by making a simple POST request
        try {
            const response = await fetch('/infra/servers', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'X-Admin-Username': username,
                    'X-Admin-Password': password
                },
                body: JSON.stringify({ serverName: '' }) // Empty request to test auth
            });
            
            if (response.status === 401 || response.status === 403) {
                this.showAdminStatus('Invalid credentials', 'error');
                return;
            }
            
            // If we get a 400 (bad request because serverName is empty), auth is OK
            if (response.status === 400 || response.ok) {
                this.adminCredentials = { username, password };
                sessionStorage.setItem('adminCredentials', JSON.stringify(this.adminCredentials));
                this.isAdmin = true;
                this.showAdminStatus('Logged in successfully!', 'success');
                this.updateAdminUI();
                return;
            }
            
            this.showAdminStatus('Login failed', 'error');
        } catch (error) {
            console.error('Login error:', error);
            this.showAdminStatus('Connection error', 'error');
        }
    }
    
    showAdminStatus(message, type) {
        const status = document.getElementById('adminStatus');
        status.textContent = message;
        status.className = 'admin-status ' + type;
    }
    
    updateAdminUI() {
        const addBtn = document.getElementById('addServerBtn');
        const adminColumns = document.querySelectorAll('.admin-only');
        
        if (this.isAdmin) {
            addBtn.style.display = 'inline-flex';
            adminColumns.forEach(col => col.style.display = 'table-cell');
            this.renderServers();
        } else {
            addBtn.style.display = 'none';
            adminColumns.forEach(col => col.style.display = 'none');
        }
    }
    
    // Data loading
    async loadServers() {
        try {
            const response = await fetch('/infra/servers');
            const data = await response.json();
            this.servers = data.servers || [];
            this.renderServers();
        } catch (error) {
            console.error('Error loading servers:', error);
        }
    }
    
    // Rendering
    renderServers() {
        const tbody = document.getElementById('serverTableBody');
        
        if (this.servers.length === 0) {
            tbody.innerHTML = `
                <tr class="empty-row">
                    <td colspan="${this.isAdmin ? 6 : 5}">
                        <div class="empty-state">
                            <div class="empty-icon">🖥️</div>
                            <p>No servers registered yet</p>
                        </div>
                    </td>
                </tr>
            `;
            return;
        }
        
        tbody.innerHTML = this.servers.map((server, index) => `
            <tr data-id="${server.id}">
                <td>${index + 1}</td>
                <td>${this.escapeHtml(server.serverName)}</td>
                <td><span class="db-type-badge">${this.escapeHtml(server.dbType || '-')}</span></td>
                <td>${server.port || '-'}</td>
                <td class="note-cell">${this.escapeHtml(server.note || '-')}</td>
                ${this.isAdmin ? `
                    <td class="actions-cell admin-only">
                        <button class="btn btn-sm btn-secondary" onclick="serverManager.editServer(${server.id})" title="Edit">
                            ✏️
                        </button>
                        <button class="btn btn-sm btn-danger" onclick="serverManager.showDeleteModal(${server.id})" title="Delete">
                            🗑️
                        </button>
                    </td>
                ` : ''}
            </tr>
        `).join('');
    }
    
    // Modal handling
    showAddModal() {
        if (!this.isAdmin) {
            alert('Please login as admin to add servers');
            return;
        }
        
        this.editingServerId = null;
        document.getElementById('modalTitle').textContent = 'Add Server';
        document.getElementById('serverForm').reset();
        document.getElementById('serverId').value = '';
        document.getElementById('serverModal').classList.add('active');
    }
    
    editServer(id) {
        const server = this.servers.find(s => s.id === id);
        if (!server) return;
        
        this.editingServerId = id;
        document.getElementById('modalTitle').textContent = 'Edit Server';
        document.getElementById('serverId').value = id;
        document.getElementById('serverName').value = server.serverName || '';
        document.getElementById('dbType').value = server.dbType || '';
        document.getElementById('port').value = server.port || '';
        document.getElementById('note').value = server.note || '';
        document.getElementById('serverModal').classList.add('active');
    }
    
    closeModal() {
        document.getElementById('serverModal').classList.remove('active');
        this.editingServerId = null;
    }
    
    showDeleteModal(id) {
        this.deletingServerId = id;
        document.getElementById('deleteModal').classList.add('active');
    }
    
    closeDeleteModal() {
        document.getElementById('deleteModal').classList.remove('active');
        this.deletingServerId = null;
    }
    
    // CRUD Operations
    async saveServer() {
        if (!this.isAdmin || !this.adminCredentials) {
            alert('Please login as admin');
            return;
        }
        
        const serverName = document.getElementById('serverName').value.trim();
        const dbType = document.getElementById('dbType').value;
        const port = document.getElementById('port').value;
        const note = document.getElementById('note').value.trim();
        
        if (!serverName) {
            alert('Server name is required');
            return;
        }
        
        const serverData = {
            serverName,
            dbType,
            port: port ? parseInt(port) : 0,
            note
        };
        
        try {
            const isEdit = this.editingServerId !== null;
            const url = isEdit ? `/infra/servers/${this.editingServerId}` : '/infra/servers';
            const method = isEdit ? 'PUT' : 'POST';
            
            const response = await fetch(url, {
                method,
                headers: {
                    'Content-Type': 'application/json',
                    'X-Admin-Username': this.adminCredentials.username,
                    'X-Admin-Password': this.adminCredentials.password
                },
                body: JSON.stringify(serverData)
            });
            
            if (response.ok) {
                this.closeModal();
                this.loadServers();
            } else {
                const data = await response.json();
                alert(data.error || 'Failed to save server');
            }
        } catch (error) {
            console.error('Error saving server:', error);
            alert('Failed to save server');
        }
    }
    
    async confirmDelete() {
        if (!this.isAdmin || !this.adminCredentials || !this.deletingServerId) {
            return;
        }
        
        try {
            const response = await fetch(`/infra/servers/${this.deletingServerId}`, {
                method: 'DELETE',
                headers: {
                    'X-Admin-Username': this.adminCredentials.username,
                    'X-Admin-Password': this.adminCredentials.password
                }
            });
            
            if (response.ok) {
                this.closeDeleteModal();
                this.loadServers();
            } else {
                const data = await response.json();
                alert(data.error || 'Failed to delete server');
            }
        } catch (error) {
            console.error('Error deleting server:', error);
            alert('Failed to delete server');
        }
    }
    
    // Utilities
    escapeHtml(text) {
        if (!text) return '';
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }
}

// Initialize the manager when DOM is ready
document.addEventListener('DOMContentLoaded', () => {
    window.serverManager = new ServerManager();
});
