/**
 * Server Details Management - Frontend Application
 * Supports hierarchical structure: Server Groups -> Sub-Servers
 */

class ServerManager {
    constructor() {
        this.serverGroups = [];
        this.isAdmin = false;
        this.adminCredentials = null;
        this.editingGroupId = null;
        this.editingServerId = null;
        this.currentGroupId = null; // For adding sub-servers
        this.deleteType = null; // 'group' or 'server'
        this.deleteId = null;
        
        this.init();
    }
    
    init() {
        this.setupTheme();
        this.setupEventListeners();
        this.loadServerGroups();
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
        
        // Add server group button
        document.getElementById('addServerGroupBtn').addEventListener('click', () => this.showAddGroupModal());
        
        // Server Group Modal buttons
        document.getElementById('groupModalClose').addEventListener('click', () => this.closeGroupModal());
        document.getElementById('groupCancelBtn').addEventListener('click', () => this.closeGroupModal());
        document.getElementById('groupSaveBtn').addEventListener('click', () => this.saveServerGroup());
        
        // Sub-Server Modal buttons
        document.getElementById('modalClose').addEventListener('click', () => this.closeServerModal());
        document.getElementById('cancelBtn').addEventListener('click', () => this.closeServerModal());
        document.getElementById('saveBtn').addEventListener('click', () => this.saveServer());
        
        // Delete modal
        document.getElementById('deleteModalClose').addEventListener('click', () => this.closeDeleteModal());
        document.getElementById('deleteCancelBtn').addEventListener('click', () => this.closeDeleteModal());
        document.getElementById('deleteConfirmBtn').addEventListener('click', () => this.confirmDelete());
        
        // Close modals on outside click
        document.getElementById('serverGroupModal').addEventListener('click', (e) => {
            if (e.target.id === 'serverGroupModal') this.closeGroupModal();
        });
        document.getElementById('serverModal').addEventListener('click', (e) => {
            if (e.target.id === 'serverModal') this.closeServerModal();
        });
        document.getElementById('deleteModal').addEventListener('click', (e) => {
            if (e.target.id === 'deleteModal') this.closeDeleteModal();
        });
        
        // Keyboard shortcuts
        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') {
                this.closeGroupModal();
                this.closeServerModal();
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
        if (!this.adminCredentials) return;
        
        try {
            const response = await fetch('/infra/auth/validate', {
                method: 'GET',
                headers: {
                    'X-Admin-Username': this.adminCredentials.username,
                    'X-Admin-Password': this.adminCredentials.password
                }
            });
            
            const data = await response.json();
            
            if (data.valid) {
                this.isAdmin = true;
                this.updateAdminUI();
                this.loadServerGroups();
            } else {
                this.isAdmin = false;
                sessionStorage.removeItem('adminCredentials');
                this.adminCredentials = null;
            }
        } catch (error) {
            console.error('Credential validation error:', error);
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
        
        try {
            const response = await fetch('/infra/auth/validate', {
                method: 'GET',
                headers: {
                    'X-Admin-Username': username,
                    'X-Admin-Password': password
                }
            });
            
            const data = await response.json();
            
            if (!data.configured) {
                this.showAdminStatus('Admin credentials not configured on server', 'error');
                return;
            }
            
            if (data.valid) {
                this.adminCredentials = { username, password };
                sessionStorage.setItem('adminCredentials', JSON.stringify(this.adminCredentials));
                this.isAdmin = true;
                this.showAdminStatus('Logged in successfully!', 'success');
                this.updateAdminUI();
                this.loadServerGroups();
            } else {
                this.showAdminStatus(data.error || 'Invalid credentials', 'error');
            }
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
        const addBtn = document.getElementById('addServerGroupBtn');
        
        if (this.isAdmin) {
            addBtn.style.display = 'inline-flex';
        } else {
            addBtn.style.display = 'none';
        }
        
        this.renderServerGroups();
    }
    
    // Data loading
    async loadServerGroups() {
        try {
            const response = await fetch('/infra/server-groups');
            const data = await response.json();
            this.serverGroups = data.serverGroups || [];
            this.renderServerGroups();
        } catch (error) {
            console.error('Error loading server groups:', error);
        }
    }
    
    // Rendering
    renderServerGroups() {
        const container = document.getElementById('serverGroupsContainer');
        
        if (this.serverGroups.length === 0) {
            container.innerHTML = `
                <div class="empty-state">
                    <div class="empty-icon">🖥️</div>
                    <p>No server groups registered yet</p>
                </div>
            `;
            return;
        }
        
        container.innerHTML = this.serverGroups.map(group => this.renderServerGroup(group)).join('');
    }
    
    renderServerGroup(group) {
        const subServers = group.subServers || [];
        
        return `
            <div class="server-group" data-group-id="${group.id}">
                <div class="server-group-header">
                    <div class="server-group-info">
                        <span class="toggle-icon" onclick="serverManager.toggleGroup(${group.id})">▼</span>
                        <h3 class="server-group-name">${this.escapeHtml(group.serverName)}</h3>
                        ${group.note ? `<span class="server-group-note">${this.escapeHtml(group.note)}</span>` : ''}
                    </div>
                    <div class="server-group-actions">
                        ${this.isAdmin ? `
                            <button class="btn btn-sm btn-primary" onclick="serverManager.showAddServerModal(${group.id})" title="Add Sub-Server">
                                + Add
                            </button>
                            <button class="btn btn-sm btn-secondary" onclick="serverManager.editServerGroup(${group.id})" title="Edit Group">
                                ✏️
                            </button>
                            <button class="btn btn-sm btn-danger" onclick="serverManager.showDeleteGroupModal(${group.id})" title="Delete Group">
                                🗑️
                            </button>
                        ` : ''}
                    </div>
                </div>
                <div class="server-group-content" id="group-content-${group.id}">
                    ${subServers.length > 0 ? `
                        <table class="infra-table sub-server-table">
                            <thead>
                                <tr>
                                    <th>No</th>
                                    <th>Server Name</th>
                                    <th>DB Type</th>
                                    <th>Port</th>
                                    <th>Note</th>
                                    ${this.isAdmin ? '<th>Actions</th>' : ''}
                                </tr>
                            </thead>
                            <tbody>
                                ${subServers.map((server, index) => this.renderSubServer(server, index)).join('')}
                            </tbody>
                        </table>
                    ` : `
                        <div class="empty-sub-servers">
                            <p>No sub-servers in this group</p>
                        </div>
                    `}
                </div>
            </div>
        `;
    }
    
    renderSubServer(server, index) {
        return `
            <tr data-id="${server.id}">
                <td>${index + 1}</td>
                <td>${this.escapeHtml(server.serverName)}</td>
                <td><span class="db-type-badge">${this.escapeHtml(server.dbType || '-')}</span></td>
                <td>${server.port || '-'}</td>
                <td class="note-cell">${this.escapeHtml(server.note || '-')}</td>
                ${this.isAdmin ? `
                    <td class="actions-cell">
                        <button class="btn btn-sm btn-secondary" onclick="serverManager.editServer(${server.id}, ${server.groupId})" title="Edit">
                            ✏️
                        </button>
                        <button class="btn btn-sm btn-danger" onclick="serverManager.showDeleteServerModal(${server.id})" title="Delete">
                            🗑️
                        </button>
                    </td>
                ` : ''}
            </tr>
        `;
    }
    
    toggleGroup(groupId) {
        const content = document.getElementById(`group-content-${groupId}`);
        const group = content.closest('.server-group');
        const icon = group.querySelector('.toggle-icon');
        
        if (content.style.display === 'none') {
            content.style.display = 'block';
            icon.textContent = '▼';
        } else {
            content.style.display = 'none';
            icon.textContent = '▶';
        }
    }
    
    // Server Group Modal handling
    showAddGroupModal() {
        if (!this.isAdmin) {
            alert('Please login as admin to add server groups');
            return;
        }
        
        this.editingGroupId = null;
        document.getElementById('groupModalTitle').textContent = 'Add Server Group';
        document.getElementById('serverGroupForm').reset();
        document.getElementById('groupId').value = '';
        document.getElementById('serverGroupModal').classList.add('active');
    }
    
    editServerGroup(id) {
        const group = this.serverGroups.find(g => g.id === id);
        if (!group) return;
        
        this.editingGroupId = id;
        document.getElementById('groupModalTitle').textContent = 'Edit Server Group';
        document.getElementById('groupId').value = id;
        document.getElementById('groupServerName').value = group.serverName || '';
        document.getElementById('groupNote').value = group.note || '';
        document.getElementById('serverGroupModal').classList.add('active');
    }
    
    closeGroupModal() {
        document.getElementById('serverGroupModal').classList.remove('active');
        this.editingGroupId = null;
    }
    
    // Sub-Server Modal handling
    showAddServerModal(groupId) {
        if (!this.isAdmin) {
            alert('Please login as admin to add servers');
            return;
        }
        
        this.editingServerId = null;
        this.currentGroupId = groupId;
        document.getElementById('modalTitle').textContent = 'Add Sub-Server';
        document.getElementById('serverForm').reset();
        document.getElementById('serverId').value = '';
        document.getElementById('serverGroupId').value = groupId;
        document.getElementById('serverModal').classList.add('active');
    }
    
    editServer(id, groupId) {
        const group = this.serverGroups.find(g => g.id === groupId);
        if (!group) return;
        
        const server = group.subServers.find(s => s.id === id);
        if (!server) return;
        
        this.editingServerId = id;
        this.currentGroupId = groupId;
        document.getElementById('modalTitle').textContent = 'Edit Sub-Server';
        document.getElementById('serverId').value = id;
        document.getElementById('serverGroupId').value = groupId;
        document.getElementById('serverName').value = server.serverName || '';
        document.getElementById('dbType').value = server.dbType || '';
        document.getElementById('port').value = server.port || '';
        document.getElementById('note').value = server.note || '';
        document.getElementById('serverModal').classList.add('active');
    }
    
    closeServerModal() {
        document.getElementById('serverModal').classList.remove('active');
        this.editingServerId = null;
        this.currentGroupId = null;
    }
    
    // Delete Modal handling
    showDeleteGroupModal(id) {
        this.deleteType = 'group';
        this.deleteId = id;
        document.getElementById('deleteMessage').textContent = 
            'Are you sure you want to delete this server group? All sub-servers will also be deleted.';
        document.getElementById('deleteModal').classList.add('active');
    }
    
    showDeleteServerModal(id) {
        this.deleteType = 'server';
        this.deleteId = id;
        document.getElementById('deleteMessage').textContent = 
            'Are you sure you want to delete this sub-server?';
        document.getElementById('deleteModal').classList.add('active');
    }
    
    closeDeleteModal() {
        document.getElementById('deleteModal').classList.remove('active');
        this.deleteType = null;
        this.deleteId = null;
    }
    
    // CRUD Operations - Server Groups
    async saveServerGroup() {
        if (!this.isAdmin || !this.adminCredentials) {
            alert('Please login as admin');
            return;
        }
        
        const serverName = document.getElementById('groupServerName').value.trim();
        const note = document.getElementById('groupNote').value.trim();
        
        if (!serverName) {
            alert('Server name is required');
            return;
        }
        
        const groupData = { serverName, note };
        
        try {
            const isEdit = this.editingGroupId !== null;
            const url = isEdit ? `/infra/server-groups/${this.editingGroupId}` : '/infra/server-groups';
            const method = isEdit ? 'PUT' : 'POST';
            
            const response = await fetch(url, {
                method,
                headers: {
                    'Content-Type': 'application/json',
                    'X-Admin-Username': this.adminCredentials.username,
                    'X-Admin-Password': this.adminCredentials.password
                },
                body: JSON.stringify(groupData)
            });
            
            if (response.ok) {
                this.closeGroupModal();
                this.loadServerGroups();
            } else {
                const data = await response.json();
                alert(data.error || 'Failed to save server group');
            }
        } catch (error) {
            console.error('Error saving server group:', error);
            alert('Failed to save server group');
        }
    }
    
    // CRUD Operations - Sub-Servers
    async saveServer() {
        if (!this.isAdmin || !this.adminCredentials) {
            alert('Please login as admin');
            return;
        }
        
        const groupId = document.getElementById('serverGroupId').value;
        const serverName = document.getElementById('serverName').value.trim();
        const dbType = document.getElementById('dbType').value;
        const port = document.getElementById('port').value;
        const note = document.getElementById('note').value.trim();
        
        if (!serverName) {
            alert('Server name is required');
            return;
        }
        
        const serverData = {
            groupId: parseInt(groupId),
            serverName,
            dbType,
            port: port ? parseInt(port) : 0,
            note
        };
        
        try {
            const isEdit = this.editingServerId !== null;
            const url = isEdit ? `/infra/servers/${this.editingServerId}` : `/infra/server-groups/${groupId}/servers`;
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
                this.closeServerModal();
                this.loadServerGroups();
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
        if (!this.isAdmin || !this.adminCredentials || !this.deleteId) {
            return;
        }
        
        try {
            let url;
            if (this.deleteType === 'group') {
                url = `/infra/server-groups/${this.deleteId}`;
            } else {
                url = `/infra/servers/${this.deleteId}`;
            }
            
            const response = await fetch(url, {
                method: 'DELETE',
                headers: {
                    'X-Admin-Username': this.adminCredentials.username,
                    'X-Admin-Password': this.adminCredentials.password
                }
            });
            
            if (response.ok) {
                this.closeDeleteModal();
                this.loadServerGroups();
            } else {
                const data = await response.json();
                alert(data.error || 'Failed to delete');
            }
        } catch (error) {
            console.error('Error deleting:', error);
            alert('Failed to delete');
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
