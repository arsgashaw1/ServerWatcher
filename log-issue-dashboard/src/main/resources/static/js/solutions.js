/**
 * Solutions Knowledge Base - Frontend Application
 */

class SolutionsManager {
    constructor() {
        this.solutions = [];
        this.selectedSolution = null;
        this.pagination = {
            offset: 0,
            limit: 20,
            total: 0
        };
        this.filters = {
            search: '',
            sort: 'upvotes',
            includeArchived: false
        };
        this.pendingAction = null;
        
        this.init();
    }
    
    init() {
        this.setupTheme();
        this.setupEventListeners();
        this.loadSolutions();
        this.loadStats();
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
        // Theme and refresh
        document.getElementById('themeToggle').addEventListener('click', () => this.toggleTheme());
        document.getElementById('refreshBtn').addEventListener('click', () => this.refresh());
        
        // Add solution button
        document.getElementById('addSolutionBtn').addEventListener('click', () => this.showAddModal());
        
        // Search
        document.getElementById('searchBtn').addEventListener('click', () => this.performSearch());
        document.getElementById('searchInput').addEventListener('keypress', (e) => {
            if (e.key === 'Enter') this.performSearch();
        });
        
        // Filters
        document.getElementById('sortSelect').addEventListener('change', (e) => {
            this.filters.sort = e.target.value;
            this.pagination.offset = 0;
            this.loadSolutions();
        });
        document.getElementById('includeArchived').addEventListener('change', (e) => {
            this.filters.includeArchived = e.target.checked;
            this.pagination.offset = 0;
            this.loadSolutions();
        });
        
        // Pagination
        document.getElementById('prevPage').addEventListener('click', () => this.goToPage('prev'));
        document.getElementById('nextPage').addEventListener('click', () => this.goToPage('next'));
        
        // Solution modal
        document.getElementById('modalClose').addEventListener('click', () => this.closeModal());
        document.getElementById('cancelBtn').addEventListener('click', () => this.closeModal());
        document.getElementById('saveSolutionBtn').addEventListener('click', () => this.saveSolution());
        document.getElementById('solutionModal').addEventListener('click', (e) => {
            if (e.target.id === 'solutionModal') this.closeModal();
        });
        
        // View modal
        document.getElementById('viewModalClose').addEventListener('click', () => this.closeViewModal());
        document.getElementById('viewModal').addEventListener('click', (e) => {
            if (e.target.id === 'viewModal') this.closeViewModal();
        });
        document.getElementById('editSolutionBtn').addEventListener('click', () => this.editSelectedSolution());
        document.getElementById('archiveSolutionBtn').addEventListener('click', () => this.confirmArchive());
        document.getElementById('upvoteBtn').addEventListener('click', () => this.voteSolution(true));
        document.getElementById('downvoteBtn').addEventListener('click', () => this.voteSolution(false));
        
        // Confirm modal
        document.getElementById('confirmModalClose').addEventListener('click', () => this.closeConfirmModal());
        document.getElementById('confirmCancelBtn').addEventListener('click', () => this.closeConfirmModal());
        document.getElementById('confirmActionBtn').addEventListener('click', () => this.executePendingAction());
        document.getElementById('confirmModal').addEventListener('click', (e) => {
            if (e.target.id === 'confirmModal') this.closeConfirmModal();
        });
        
        // Keyboard shortcuts
        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') {
                this.closeModal();
                this.closeViewModal();
                this.closeConfirmModal();
            }
        });
    }
    
    // Data loading
    async loadSolutions() {
        try {
            const params = new URLSearchParams({
                offset: this.pagination.offset,
                limit: this.pagination.limit,
                includeArchived: this.filters.includeArchived
            });
            
            let url = `/solutions?${params}`;
            
            // If searching, use search endpoint
            if (this.filters.search) {
                url = `/solutions/search?q=${encodeURIComponent(this.filters.search)}`;
            }
            
            const response = await fetch(url);
            const data = await response.json();
            
            this.solutions = data.solutions || [];
            this.pagination.total = data.total || this.solutions.length;
            
            // Sort locally if needed (API might not support all sort options)
            this.sortSolutions();
            
            this.renderSolutions();
            this.updatePagination();
        } catch (error) {
            console.error('Error loading solutions:', error);
            this.showError('Failed to load solutions');
        }
    }
    
    sortSolutions() {
        switch (this.filters.sort) {
            case 'upvotes':
                this.solutions.sort((a, b) => b.upvotes - a.upvotes);
                break;
            case 'usage':
                this.solutions.sort((a, b) => b.usageCount - a.usageCount);
                break;
            case 'recent':
                this.solutions.sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt));
                break;
        }
    }
    
    async loadStats() {
        try {
            const response = await fetch('/solutions/stats');
            const stats = await response.json();
            
            document.getElementById('statTotal').textContent = stats.totalAll || 0;
            document.getElementById('statActive').textContent = stats.totalActive || 0;
            
            // Calculate total usage and upvotes from top solutions
            let totalUsage = 0;
            let totalUpvotes = 0;
            if (stats.topSolutions) {
                stats.topSolutions.forEach(s => {
                    totalUsage += s.usageCount || 0;
                    totalUpvotes += s.upvotes || 0;
                });
            }
            document.getElementById('statUsage').textContent = totalUsage;
            document.getElementById('statHelpful').textContent = totalUpvotes;
        } catch (error) {
            console.error('Error loading stats:', error);
        }
    }
    
    // Rendering
    renderSolutions() {
        const list = document.getElementById('solutionsList');
        
        if (this.solutions.length === 0) {
            list.innerHTML = `
                <div class="empty-state">
                    <div class="empty-icon">💡</div>
                    <p>${this.filters.search ? 'No solutions match your search' : 'No solutions yet'}</p>
                    <p class="empty-hint">${this.filters.search ? 'Try different keywords' : 'Click "Add Solution" to create your first solution'}</p>
                </div>
            `;
            return;
        }
        
        list.innerHTML = this.solutions.map(solution => this.createSolutionCard(solution)).join('');
        
        // Attach click handlers
        list.querySelectorAll('.solution-card').forEach(card => {
            card.addEventListener('click', () => this.showSolutionDetail(card.dataset.id));
        });
    }
    
    createSolutionCard(solution) {
        const rating = this.renderRating(solution.rating || 0);
        const statusClass = solution.status === 'ARCHIVED' ? 'archived' : '';
        
        return `
            <div class="solution-card ${statusClass}" data-id="${solution.id}">
                <div class="solution-header">
                    <span class="solution-pattern">${this.escapeHtml(solution.issuePattern)}</span>
                    <div class="solution-rating">${rating} <span class="vote-count">(${solution.upvotes})</span></div>
                </div>
                <h3 class="solution-title">${this.escapeHtml(solution.title)}</h3>
                <p class="solution-description">${this.truncate(this.escapeHtml(solution.description), 150)}</p>
                <div class="solution-footer">
                    <span class="solution-meta">📅 ${solution.createdAt}</span>
                    <span class="solution-meta">📊 Used ${solution.usageCount} times</span>
                    ${solution.status === 'ARCHIVED' ? '<span class="solution-badge archived">Archived</span>' : ''}
                </div>
            </div>
        `;
    }
    
    renderRating(rating) {
        const fullStars = Math.floor(rating);
        const hasHalf = rating % 1 >= 0.5;
        let stars = '';
        
        for (let i = 0; i < 5; i++) {
            if (i < fullStars) {
                stars += '★';
            } else if (i === fullStars && hasHalf) {
                stars += '☆';
            } else {
                stars += '☆';
            }
        }
        
        return `<span class="stars">${stars}</span>`;
    }
    
    updatePagination() {
        const start = this.pagination.offset + 1;
        const end = Math.min(this.pagination.offset + this.solutions.length, this.pagination.total);
        const total = this.pagination.total;
        
        document.getElementById('paginationInfo').textContent = 
            total > 0 ? `Showing ${start}-${end} of ${total}` : 'No solutions found';
        
        const currentPage = Math.floor(this.pagination.offset / this.pagination.limit) + 1;
        const totalPages = Math.ceil(total / this.pagination.limit) || 1;
        document.getElementById('pageNumber').textContent = `${currentPage} / ${totalPages}`;
        
        document.getElementById('prevPage').disabled = this.pagination.offset === 0;
        document.getElementById('nextPage').disabled = this.pagination.offset + this.solutions.length >= total;
    }
    
    goToPage(direction) {
        if (direction === 'prev' && this.pagination.offset > 0) {
            this.pagination.offset = Math.max(0, this.pagination.offset - this.pagination.limit);
        } else if (direction === 'next') {
            this.pagination.offset += this.pagination.limit;
        }
        this.loadSolutions();
    }
    
    // Search
    performSearch() {
        this.filters.search = document.getElementById('searchInput').value.trim();
        this.pagination.offset = 0;
        this.loadSolutions();
    }
    
    // Solution CRUD
    showAddModal() {
        document.getElementById('modalTitle').textContent = 'Add New Solution';
        document.getElementById('solutionForm').reset();
        document.getElementById('solutionId').value = '';
        document.getElementById('solutionModal').classList.add('active');
    }
    
    showEditModal(solution) {
        if (!solution) {
            console.error('showEditModal: solution is null or undefined');
            this.showError('Could not load solution for editing');
            return;
        }
        
        try {
            document.getElementById('modalTitle').textContent = 'Edit Solution';
            document.getElementById('solutionId').value = solution.id || '';
            document.getElementById('issuePattern').value = solution.issuePattern || '';
            document.getElementById('messagePattern').value = solution.messagePattern || '';
            document.getElementById('stackPattern').value = solution.stackPattern || '';
            document.getElementById('solutionTitle').value = solution.title || '';
            document.getElementById('solutionDescription').value = solution.description || '';
            document.getElementById('solutionModal').classList.add('active');
        } catch (error) {
            console.error('showEditModal error:', error);
            this.showError('Failed to open edit modal');
        }
    }
    
    closeModal() {
        document.getElementById('solutionModal').classList.remove('active');
    }
    
    async saveSolution() {
        const form = document.getElementById('solutionForm');
        if (!form.checkValidity()) {
            form.reportValidity();
            return;
        }
        
        const id = document.getElementById('solutionId').value;
        const data = {
            issuePattern: document.getElementById('issuePattern').value,
            messagePattern: document.getElementById('messagePattern').value || null,
            stackPattern: document.getElementById('stackPattern').value || null,
            title: document.getElementById('solutionTitle').value,
            description: document.getElementById('solutionDescription').value
        };
        
        try {
            const url = id ? `/solutions/${id}` : '/solutions';
            const method = id ? 'PUT' : 'POST';
            
            const response = await fetch(url, {
                method,
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            
            if (response.ok) {
                this.closeModal();
                this.loadSolutions();
                this.loadStats();
                this.showSuccess(id ? 'Solution updated' : 'Solution created');
            } else {
                const error = await response.json();
                this.showError(error.error || 'Failed to save solution');
            }
        } catch (error) {
            console.error('Error saving solution:', error);
            this.showError('Failed to save solution');
        }
    }
    
    // View solution detail
    async showSolutionDetail(id) {
        try {
            const response = await fetch(`/solutions/${id}`);
            if (!response.ok) {
                this.showError('Solution not found');
                return;
            }
            
            const solution = await response.json();
            this.selectedSolution = solution;
            
            document.getElementById('viewModalTitle').textContent = solution.title;
            document.getElementById('viewModalBody').innerHTML = `
                <div class="solution-detail">
                    <div class="detail-section">
                        <div class="detail-row">
                            <div class="detail-label">Issue Pattern</div>
                            <div class="detail-value pattern-value">${this.escapeHtml(solution.issuePattern)}</div>
                        </div>
                        ${solution.messagePattern ? `
                        <div class="detail-row">
                            <div class="detail-label">Message Pattern</div>
                            <div class="detail-value"><code>${this.escapeHtml(solution.messagePattern)}</code></div>
                        </div>
                        ` : ''}
                        ${solution.stackPattern ? `
                        <div class="detail-row">
                            <div class="detail-label">Stack Pattern</div>
                            <div class="detail-value"><code>${this.escapeHtml(solution.stackPattern)}</code></div>
                        </div>
                        ` : ''}
                    </div>
                    
                    <div class="detail-section">
                        <div class="detail-row">
                            <div class="detail-label">Rating</div>
                            <div class="detail-value">
                                ${this.renderRating(solution.rating || 0)}
                                <span class="vote-info">${solution.upvotes} upvotes, ${solution.downvotes} downvotes</span>
                            </div>
                        </div>
                        <div class="detail-row">
                            <div class="detail-label">Usage</div>
                            <div class="detail-value">Suggested ${solution.usageCount} times</div>
                        </div>
                        <div class="detail-row">
                            <div class="detail-label">Created</div>
                            <div class="detail-value">${solution.createdAt} ${solution.createdBy ? `by ${this.escapeHtml(solution.createdBy)}` : ''}</div>
                        </div>
                        <div class="detail-row">
                            <div class="detail-label">Status</div>
                            <div class="detail-value">
                                <span class="solution-badge ${solution.status.toLowerCase()}">${solution.status}</span>
                            </div>
                        </div>
                    </div>
                    
                    <div class="detail-section">
                        <div class="detail-label">Solution Description</div>
                        <div class="solution-content">${this.renderMarkdown(solution.description)}</div>
                    </div>
                </div>
            `;
            
            // Update button states
            document.getElementById('archiveSolutionBtn').textContent = 
                solution.status === 'ARCHIVED' ? '📤 Restore' : '📦 Archive';
            
            document.getElementById('viewModal').classList.add('active');
        } catch (error) {
            console.error('Error loading solution:', error);
            this.showError('Failed to load solution details');
        }
    }
    
    closeViewModal() {
        document.getElementById('viewModal').classList.remove('active');
        this.selectedSolution = null;
    }
    
    editSelectedSolution() {
        if (!this.selectedSolution) {
            console.error('editSelectedSolution: no solution selected');
            this.showError('No solution selected');
            return;
        }
        
        // Create a copy of the solution object before closing modal
        const solution = { ...this.selectedSolution };
        console.log('Editing solution:', solution);
        
        // Close view modal first
        document.getElementById('viewModal').classList.remove('active');
        
        // Then open edit modal (don't set selectedSolution to null yet)
        this.showEditModal(solution);
        
        // Now clear the selected solution
        this.selectedSolution = null;
    }
    
    // Voting
    async voteSolution(isUpvote) {
        if (!this.selectedSolution) return;
        
        try {
            const endpoint = isUpvote ? 'upvote' : 'downvote';
            const response = await fetch(`/solutions/${this.selectedSolution.id}/${endpoint}`, {
                method: 'POST'
            });
            
            if (response.ok) {
                const result = await response.json();
                this.selectedSolution.upvotes = result.upvotes;
                this.selectedSolution.downvotes = result.downvotes;
                this.selectedSolution.rating = result.rating;
                
                // Update the view
                this.showSolutionDetail(this.selectedSolution.id);
                this.loadSolutions();
                this.showSuccess(isUpvote ? 'Upvoted!' : 'Downvoted');
            }
        } catch (error) {
            console.error('Error voting:', error);
            this.showError('Failed to record vote');
        }
    }
    
    // Archive/Restore
    confirmArchive() {
        if (!this.selectedSolution) return;
        
        const isArchived = this.selectedSolution.status === 'ARCHIVED';
        document.getElementById('confirmMessage').textContent = isArchived 
            ? 'Are you sure you want to restore this solution?'
            : 'Are you sure you want to archive this solution?';
        
        this.pendingAction = () => this.archiveSolution();
        document.getElementById('confirmModal').classList.add('active');
    }
    
    async archiveSolution() {
        if (!this.selectedSolution) return;
        
        try {
            const response = await fetch(`/solutions/${this.selectedSolution.id}/archive`, {
                method: 'POST'
            });
            
            if (response.ok) {
                this.closeConfirmModal();
                this.closeViewModal();
                this.loadSolutions();
                this.loadStats();
                this.showSuccess('Solution archived');
            }
        } catch (error) {
            console.error('Error archiving:', error);
            this.showError('Failed to archive solution');
        }
    }
    
    closeConfirmModal() {
        document.getElementById('confirmModal').classList.remove('active');
        this.pendingAction = null;
    }
    
    executePendingAction() {
        if (this.pendingAction) {
            this.pendingAction();
        }
    }
    
    // Refresh
    async refresh() {
        const btn = document.getElementById('refreshBtn');
        btn.textContent = '⏳';
        
        await Promise.all([
            this.loadSolutions(),
            this.loadStats()
        ]);
        
        btn.textContent = '🔄';
    }
    
    // Utilities
    escapeHtml(text) {
        if (!text) return '';
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }
    
    truncate(text, length) {
        if (!text || text.length <= length) return text;
        return text.substring(0, length) + '...';
    }
    
    renderMarkdown(text) {
        if (!text) return '';
        
        // Simple markdown rendering
        let html = this.escapeHtml(text);
        
        // Headers
        html = html.replace(/^### (.+)$/gm, '<h4>$1</h4>');
        html = html.replace(/^## (.+)$/gm, '<h3>$1</h3>');
        html = html.replace(/^# (.+)$/gm, '<h2>$1</h2>');
        
        // Bold and italic
        html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
        html = html.replace(/\*(.+?)\*/g, '<em>$1</em>');
        
        // Code blocks
        html = html.replace(/```(\w*)\n([\s\S]*?)```/g, '<pre><code>$2</code></pre>');
        html = html.replace(/`([^`]+)`/g, '<code>$1</code>');
        
        // Lists
        html = html.replace(/^\d+\. (.+)$/gm, '<li>$1</li>');
        html = html.replace(/^- (.+)$/gm, '<li>$1</li>');
        
        // Line breaks
        html = html.replace(/\n\n/g, '</p><p>');
        html = html.replace(/\n/g, '<br>');
        
        return `<p>${html}</p>`;
    }
    
    showSuccess(message) {
        // Could be replaced with a toast notification
        console.log('Success:', message);
    }
    
    showError(message) {
        // Could be replaced with a toast notification
        console.error('Error:', message);
        alert(message);
    }
}

// Initialize when DOM is ready
document.addEventListener('DOMContentLoaded', () => {
    window.solutionsManager = new SolutionsManager();
});
