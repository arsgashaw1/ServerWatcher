/**
 * VM Machine Details Management - Frontend Application
 */

class VmManager {
    constructor() {
        this.vms = [];
        this.isAdmin = false;
        this.adminCredentials = null;
        this.editingVmId = null;
        this.deletingVmId = null;
        this.showPasswords = {};
        
        this.init();
    }
    
    init() {
        this.setupTheme();
        this.setupEventListeners();
        this.loadVms();
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
        
        // Add VM button
        document.getElementById('addVmBtn').addEventListener('click', () => this.showAddModal());
        
        // Modal buttons
        document.getElementById('modalClose').addEventListener('click', () => this.closeModal());
        document.getElementById('cancelBtn').addEventListener('click', () => this.closeModal());
        document.getElementById('saveBtn').addEventListener('click', () => this.saveVm());
        
        // Password toggle in form
        document.getElementById('togglePassword').addEventListener('click', () => this.toggleFormPassword());
        
        // Delete modal
        document.getElementById('deleteModalClose').addEventListener('click', () => this.closeDeleteModal());
        document.getElementById('deleteCancelBtn').addEventListener('click', () => this.closeDeleteModal());
        document.getElementById('deleteConfirmBtn').addEventListener('click', () => this.confirmDelete());
        
        // Close modals on outside click
        document.getElementById('vmModal').addEventListener('click', (e) => {
            if (e.target.id === 'vmModal') this.closeModal();
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
        // Validate stored credentials using the auth endpoint
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
                this.loadVms(); // Reload to get full data with credentials
            } else {
                // Invalid credentials - clear them
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
        
        // Validate credentials using the auth endpoint
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
                this.loadVms(); // Reload to get full data with credentials
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
        const addBtn = document.getElementById('addVmBtn');
        const adminColumns = document.querySelectorAll('.admin-only');
        
        if (this.isAdmin) {
            addBtn.style.display = 'inline-flex';
            adminColumns.forEach(col => col.style.display = 'table-cell');
            this.renderVms();
        } else {
            addBtn.style.display = 'none';
            adminColumns.forEach(col => col.style.display = 'none');
        }
    }
    
    // Data loading
    async loadVms() {
        try {
            const headers = {};
            // Include credentials if authenticated to get unmasked passwords
            if (this.isAdmin && this.adminCredentials) {
                headers['X-Admin-Username'] = this.adminCredentials.username;
                headers['X-Admin-Password'] = this.adminCredentials.password;
            }
            
            const response = await fetch('/infra/vms', { headers });
            const data = await response.json();
            this.vms = data.vms || [];
            this.renderVms();
        } catch (error) {
            console.error('Error loading VMs:', error);
        }
    }
    
    // Rendering
    renderVms() {
        const tbody = document.getElementById('vmTableBody');
        
        if (this.vms.length === 0) {
            tbody.innerHTML = `
                <tr class="empty-row">
                    <td colspan="${this.isAdmin ? 6 : 5}">
                        <div class="empty-state">
                            <div class="empty-icon">💻</div>
                            <p>No VMs registered yet</p>
                        </div>
                    </td>
                </tr>
            `;
            return;
        }
        
        tbody.innerHTML = this.vms.map((vm, index) => {
            // Check if password is available (only for authenticated users)
            const hasPassword = vm.hasPassword || (vm.password && vm.password !== '********');
            const passwordDisplay = this.showPasswords[vm.id] && this.isAdmin
                ? this.escapeHtml(vm.password || '-') 
                : (hasPassword ? '••••••••' : '-');
            
            return `
                <tr data-id="${vm.id}">
                    <td>${index + 1}</td>
                    <td>${this.escapeHtml(vm.vmName)}</td>
                    <td>${this.escapeHtml(vm.loginUsername || '-')}</td>
                    <td class="password-cell">
                        <span class="password-value ${this.showPasswords[vm.id] && this.isAdmin ? 'visible' : ''}" id="pwd-${vm.id}">
                            ${passwordDisplay}
                        </span>
                        ${hasPassword && this.isAdmin ? `
                            <button class="btn-show-password" onclick="vmManager.togglePasswordVisibility(${vm.id})" title="Show/Hide Password">
                                ${this.showPasswords[vm.id] ? '🙈' : '👁️'}
                            </button>
                        ` : ''}
                    </td>
                    <td class="portal-cell">${this.escapeHtml(vm.vmStartCredentialPortal || '-')}</td>
                    ${this.isAdmin ? `
                        <td class="actions-cell admin-only">
                            <button class="btn btn-sm btn-secondary" onclick="vmManager.editVm(${vm.id})" title="Edit">
                                ✏️
                            </button>
                            <button class="btn btn-sm btn-danger" onclick="vmManager.showDeleteModal(${vm.id})" title="Delete">
                                🗑️
                            </button>
                        </td>
                    ` : ''}
                </tr>
            `;
        }).join('');
    }
    
    togglePasswordVisibility(vmId) {
        this.showPasswords[vmId] = !this.showPasswords[vmId];
        this.renderVms();
    }
    
    toggleFormPassword() {
        const passwordInput = document.getElementById('vmPassword');
        const toggleBtn = document.getElementById('togglePassword');
        
        if (passwordInput.type === 'password') {
            passwordInput.type = 'text';
            toggleBtn.textContent = '🙈';
        } else {
            passwordInput.type = 'password';
            toggleBtn.textContent = '👁️';
        }
    }
    
    // Modal handling
    showAddModal() {
        if (!this.isAdmin) {
            alert('Please login as admin to add VMs');
            return;
        }
        
        this.editingVmId = null;
        document.getElementById('modalTitle').textContent = 'Add VM';
        document.getElementById('vmForm').reset();
        document.getElementById('vmId').value = '';
        document.getElementById('vmPassword').type = 'password';
        document.getElementById('togglePassword').textContent = '👁️';
        document.getElementById('vmModal').classList.add('active');
    }
    
    editVm(id) {
        const vm = this.vms.find(v => v.id === id);
        if (!vm) return;
        
        this.editingVmId = id;
        document.getElementById('modalTitle').textContent = 'Edit VM';
        document.getElementById('vmId').value = id;
        document.getElementById('vmName').value = vm.vmName || '';
        document.getElementById('loginUsername').value = vm.loginUsername || '';
        document.getElementById('vmPassword').value = vm.password || '';
        document.getElementById('vmStartCredentialPortal').value = vm.vmStartCredentialPortal || '';
        document.getElementById('vmPassword').type = 'password';
        document.getElementById('togglePassword').textContent = '👁️';
        document.getElementById('vmModal').classList.add('active');
    }
    
    closeModal() {
        document.getElementById('vmModal').classList.remove('active');
        this.editingVmId = null;
    }
    
    showDeleteModal(id) {
        this.deletingVmId = id;
        document.getElementById('deleteModal').classList.add('active');
    }
    
    closeDeleteModal() {
        document.getElementById('deleteModal').classList.remove('active');
        this.deletingVmId = null;
    }
    
    // CRUD Operations
    async saveVm() {
        if (!this.isAdmin || !this.adminCredentials) {
            alert('Please login as admin');
            return;
        }
        
        const vmName = document.getElementById('vmName').value.trim();
        const loginUsername = document.getElementById('loginUsername').value.trim();
        const password = document.getElementById('vmPassword').value;
        const vmStartCredentialPortal = document.getElementById('vmStartCredentialPortal').value.trim();
        
        if (!vmName) {
            alert('VM name is required');
            return;
        }
        
        const vmData = {
            vmName,
            loginUsername,
            password,
            vmStartCredentialPortal
        };
        
        try {
            const isEdit = this.editingVmId !== null;
            const url = isEdit ? `/infra/vms/${this.editingVmId}` : '/infra/vms';
            const method = isEdit ? 'PUT' : 'POST';
            
            const response = await fetch(url, {
                method,
                headers: {
                    'Content-Type': 'application/json',
                    'X-Admin-Username': this.adminCredentials.username,
                    'X-Admin-Password': this.adminCredentials.password
                },
                body: JSON.stringify(vmData)
            });
            
            if (response.ok) {
                this.closeModal();
                this.loadVms();
            } else {
                const data = await response.json();
                alert(data.error || 'Failed to save VM');
            }
        } catch (error) {
            console.error('Error saving VM:', error);
            alert('Failed to save VM');
        }
    }
    
    async confirmDelete() {
        if (!this.isAdmin || !this.adminCredentials || !this.deletingVmId) {
            return;
        }
        
        try {
            const response = await fetch(`/infra/vms/${this.deletingVmId}`, {
                method: 'DELETE',
                headers: {
                    'X-Admin-Username': this.adminCredentials.username,
                    'X-Admin-Password': this.adminCredentials.password
                }
            });
            
            if (response.ok) {
                this.closeDeleteModal();
                this.loadVms();
            } else {
                const data = await response.json();
                alert(data.error || 'Failed to delete VM');
            }
        } catch (error) {
            console.error('Error deleting VM:', error);
            alert('Failed to delete VM');
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
    window.vmManager = new VmManager();
});
