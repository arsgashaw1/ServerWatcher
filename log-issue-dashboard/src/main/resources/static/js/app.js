/**
 * Log Issue Dashboard - Frontend Application
 */

class LogDashboard {
    constructor() {
        this.issues = [];
        this.eventSource = null;
        this.charts = {};
        this.selectedIssue = null;
        this.filters = {
            severity: '',
            server: '',
            dateFrom: '',
            dateTo: ''
        };
        this.pagination = {
            offset: 0,
            limit: 50,
            total: 0,
            totalFiltered: 0
        };
        
        this.init();
    }
    
    init() {
        this.setupTheme();
        this.setupEventListeners();
        this.setupTabs();
        this.initCharts();
        this.connectEventStream();
        this.loadInitialData();
        
        // Refresh data periodically
        setInterval(() => this.loadStats(), 10000);
        setInterval(() => this.loadAnomalies(), 30000);
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
        this.updateChartColors();
    }
    
    updateThemeButton(theme) {
        const btn = document.getElementById('themeToggle');
        btn.textContent = theme === 'dark' ? '☀️' : '🌙';
    }
    
    // Event listeners
    setupEventListeners() {
        document.getElementById('themeToggle').addEventListener('click', () => this.toggleTheme());
        document.getElementById('refreshBtn').addEventListener('click', () => this.refresh());
        document.getElementById('clearBtn').addEventListener('click', () => this.clearAll());
        
        // Filter listeners
        document.getElementById('severityFilter').addEventListener('change', (e) => this.filterBySeverity(e.target.value));
        document.getElementById('serverFilter').addEventListener('change', (e) => this.filterByServer(e.target.value));
        document.getElementById('dateFromFilter').addEventListener('change', (e) => this.filterByDateFrom(e.target.value));
        document.getElementById('dateToFilter').addEventListener('change', (e) => this.filterByDateTo(e.target.value));
        
        // Quick date filters
        document.getElementById('quickFilterToday').addEventListener('click', () => this.setQuickDateFilter('today'));
        document.getElementById('quickFilter7d').addEventListener('click', () => this.setQuickDateFilter('7d'));
        document.getElementById('quickFilter30d').addEventListener('click', () => this.setQuickDateFilter('30d'));
        document.getElementById('clearFilters').addEventListener('click', () => this.clearFilters());
        
        // Pagination
        document.getElementById('prevPage').addEventListener('click', () => this.goToPage('prev'));
        document.getElementById('nextPage').addEventListener('click', () => this.goToPage('next'));
        document.getElementById('pageSize').addEventListener('change', (e) => this.changePageSize(parseInt(e.target.value)));
        
        // Export
        document.getElementById('exportBtn').addEventListener('click', () => this.showExportModal());
        document.getElementById('exportModalClose').addEventListener('click', () => this.closeExportModal());
        document.getElementById('exportCancelBtn').addEventListener('click', () => this.closeExportModal());
        document.getElementById('exportConfirmBtn').addEventListener('click', () => this.exportIssues());
        document.getElementById('exportModal').addEventListener('click', (e) => {
            if (e.target.id === 'exportModal') this.closeExportModal();
        });
        
        // Issue modal
        document.getElementById('modalClose').addEventListener('click', () => this.closeModal());
        document.getElementById('acknowledgeBtn').addEventListener('click', () => this.acknowledgeIssue());
        document.getElementById('copyStackTrace').addEventListener('click', () => this.copyStackTrace());
        
        // Close modal on outside click
        document.getElementById('issueModal').addEventListener('click', (e) => {
            if (e.target.id === 'issueModal') this.closeModal();
        });
        
        // Keyboard shortcuts
        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') {
                this.closeModal();
                this.closeExportModal();
            }
        });
    }
    
    setupTabs() {
        document.querySelectorAll('.tab').forEach(tab => {
            tab.addEventListener('click', () => {
                document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
                document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
                
                tab.classList.add('active');
                document.getElementById('tab-' + tab.dataset.tab).classList.add('active');
            });
        });
    }
    
    // Server-Sent Events
    connectEventStream() {
        this.updateConnectionStatus('connecting');
        
        this.eventSource = new EventSource('/events');
        
        this.eventSource.onopen = () => {
            console.log('SSE connected');
            this.updateConnectionStatus('connected');
        };
        
        this.eventSource.addEventListener('connected', (e) => {
            console.log('Connection confirmed:', e.data);
            this.updateConnectionStatus('connected');
        });
        
        this.eventSource.addEventListener('issue', (e) => {
            const issue = JSON.parse(e.data);
            this.addIssue(issue, true);
            this.updateStats();
        });
        
        this.eventSource.addEventListener('stats', (e) => {
            const stats = JSON.parse(e.data);
            this.displayStats(stats);
        });
        
        this.eventSource.onerror = () => {
            console.log('SSE error, reconnecting...');
            this.updateConnectionStatus('disconnected');
            
            // Reconnect after 5 seconds
            setTimeout(() => {
                if (this.eventSource.readyState === EventSource.CLOSED) {
                    this.connectEventStream();
                }
            }, 5000);
        };
    }
    
    updateConnectionStatus(status) {
        const el = document.getElementById('connectionStatus');
        el.className = 'connection-status ' + status;
        
        const textEl = el.querySelector('.status-text');
        switch(status) {
            case 'connected':
                textEl.textContent = 'Connected';
                break;
            case 'disconnected':
                textEl.textContent = 'Disconnected';
                break;
            default:
                textEl.textContent = 'Connecting...';
        }
    }
    
    // Data loading
    async loadInitialData() {
        try {
            await Promise.all([
                this.loadIssues(),
                this.loadStats(),
                this.loadAnalysis(),
                this.loadAnomalies(),
                this.loadServers(),
                this.loadDateRange()
            ]);
        } catch (error) {
            console.error('Error loading initial data:', error);
        }
    }
    
    async loadIssues() {
        try {
            const params = new URLSearchParams({
                offset: this.pagination.offset,
                limit: this.pagination.limit
            });
            
            if (this.filters.severity) params.append('severity', this.filters.severity);
            if (this.filters.server) params.append('server', this.filters.server);
            if (this.filters.dateFrom) params.append('from', this.filters.dateFrom);
            if (this.filters.dateTo) params.append('to', this.filters.dateTo);
            
            const response = await fetch(`/api/issues?${params}`);
            const data = await response.json();
            
            this.issues = data.issues || [];
            this.pagination.total = data.total || 0;
            this.pagination.totalFiltered = data.totalFiltered || 0;
            
            this.renderIssues();
            this.updatePagination(data);
            this.updateIssueCount();
        } catch (error) {
            console.error('Error loading issues:', error);
        }
    }
    
    updateIssueCount() {
        const countEl = document.getElementById('issueCount');
        if (this.filters.severity || this.filters.server || this.filters.dateFrom || this.filters.dateTo) {
            countEl.textContent = `(${this.pagination.totalFiltered} of ${this.pagination.total} issues)`;
        } else {
            countEl.textContent = `(${this.pagination.total} issues)`;
        }
    }
    
    updatePagination(data) {
        const start = this.pagination.offset + 1;
        const end = Math.min(this.pagination.offset + this.issues.length, this.pagination.totalFiltered);
        const total = this.pagination.totalFiltered;
        
        document.getElementById('paginationInfo').textContent = 
            total > 0 ? `Showing ${start}-${end} of ${total}` : 'No issues found';
        
        const currentPage = Math.floor(this.pagination.offset / this.pagination.limit) + 1;
        const totalPages = Math.ceil(total / this.pagination.limit);
        document.getElementById('pageNumber').textContent = `${currentPage} / ${totalPages || 1}`;
        
        document.getElementById('prevPage').disabled = this.pagination.offset === 0;
        document.getElementById('nextPage').disabled = !data.hasMore;
    }
    
    goToPage(direction) {
        if (direction === 'prev' && this.pagination.offset > 0) {
            this.pagination.offset = Math.max(0, this.pagination.offset - this.pagination.limit);
        } else if (direction === 'next') {
            this.pagination.offset += this.pagination.limit;
        }
        this.loadIssues();
    }
    
    changePageSize(size) {
        this.pagination.limit = size;
        this.pagination.offset = 0;
        this.loadIssues();
    }
    
    async loadStats() {
        try {
            const response = await fetch('/api/stats/dashboard');
            const stats = await response.json();
            this.displayStats(stats);
            this.updateCharts(stats);
        } catch (error) {
            console.error('Error loading stats:', error);
        }
    }
    
    async loadAnalysis() {
        try {
            const response = await fetch('/api/analysis');
            const analysis = await response.json();
            this.displayAnalysis(analysis);
        } catch (error) {
            console.error('Error loading analysis:', error);
        }
    }
    
    async loadAnomalies() {
        try {
            const response = await fetch('/api/analysis/anomalies');
            const anomalies = await response.json();
            this.displayAnomalies(anomalies);
        } catch (error) {
            console.error('Error loading anomalies:', error);
        }
    }
    
    async loadServers() {
        try {
            const response = await fetch('/api/servers');
            const servers = await response.json();
            this.updateServerFilter(servers);
        } catch (error) {
            console.error('Error loading servers:', error);
        }
    }
    
    async loadDateRange() {
        try {
            const response = await fetch('/api/daterange');
            const data = await response.json();
            
            // Set date picker constraints based on available data
            const fromInput = document.getElementById('dateFromFilter');
            const toInput = document.getElementById('dateToFilter');
            
            if (data.earliest) {
                fromInput.min = data.earliest;
                toInput.min = data.earliest;
            }
            if (data.latest) {
                fromInput.max = data.latest;
                toInput.max = data.latest;
            }
        } catch (error) {
            console.error('Error loading date range:', error);
        }
    }
    
    // Issue handling
    addIssue(issue, isNew = false) {
        // Always update total count (global count regardless of filters)
        this.pagination.total++;
        
        // Check if issue matches current filters
        const matchesFilters = this.issueMatchesFilters(issue);
        
        if (matchesFilters) {
            // Add to beginning of current view only if it matches filters
            this.issues.unshift(issue);
            
            // Limit issues in view
            if (this.issues.length > this.pagination.limit) {
                this.issues = this.issues.slice(0, this.pagination.limit);
            }
            
            // Update filtered count
            this.pagination.totalFiltered++;
        }
        
        // Update display
        this.updateIssueCount();
        
        // Re-render or add single item
        if (isNew && matchesFilters) {
            this.prependIssueElement(issue);
        } else if (!isNew) {
            this.renderIssues();
        }
    }
    
    /**
     * Checks if an issue matches the current filter criteria.
     */
    issueMatchesFilters(issue) {
        // Check severity filter
        if (this.filters.severity && issue.severity !== this.filters.severity) {
            return false;
        }
        
        // Check server filter
        if (this.filters.server && issue.serverName !== this.filters.server) {
            return false;
        }
        
        // Check date filters
        if (this.filters.dateFrom || this.filters.dateTo) {
            const issueDate = new Date(issue.detectedAt);
            if (this.filters.dateFrom) {
                const fromDate = new Date(this.filters.dateFrom);
                fromDate.setHours(0, 0, 0, 0);
                if (issueDate < fromDate) return false;
            }
            if (this.filters.dateTo) {
                const toDate = new Date(this.filters.dateTo);
                toDate.setHours(23, 59, 59, 999);
                if (issueDate > toDate) return false;
            }
        }
        
        return true;
    }
    
    prependIssueElement(issue) {
        const list = document.getElementById('issuesList');
        
        // Remove empty state if present
        const emptyState = list.querySelector('.empty-state');
        if (emptyState) {
            emptyState.remove();
        }
        
        const el = this.createIssueElement(issue);
        el.classList.add('new');
        list.insertBefore(el, list.firstChild);
        
        // Remove animation class after animation completes
        setTimeout(() => el.classList.remove('new'), 300);
    }
    
    renderIssues() {
        const list = document.getElementById('issuesList');
        
        // Issues are now pre-filtered from the API
        const filtered = this.issues;
        
        if (filtered.length === 0) {
            const hasFilters = this.filters.severity || this.filters.server || 
                              this.filters.dateFrom || this.filters.dateTo;
            list.innerHTML = `
                <div class="empty-state">
                    <div class="empty-icon">${hasFilters ? '🔍' : '📋'}</div>
                    <p>${hasFilters ? 'No issues match your filters' : 'No issues detected yet'}</p>
                    <p class="empty-hint">${hasFilters ? 'Try adjusting your filter criteria' : 'Issues will appear here in real-time'}</p>
                </div>
            `;
            return;
        }
        
        list.innerHTML = filtered.map(issue => this.createIssueElement(issue).outerHTML).join('');
        
        // Re-attach click handlers
        list.querySelectorAll('.issue-item').forEach(el => {
            el.addEventListener('click', () => this.showIssueDetail(el.dataset.id));
        });
    }
    
    createIssueElement(issue) {
        const el = document.createElement('div');
        el.className = `issue-item severity-${issue.severity}${issue.acknowledged ? ' acknowledged' : ''}`;
        el.dataset.id = issue.id;
        
        el.innerHTML = `
            <div class="issue-header">
                <span class="issue-type">${this.escapeHtml(issue.issueType)}</span>
                <span class="issue-badge badge-${issue.severity}">${issue.severity}</span>
            </div>
            <div class="issue-message">${this.escapeHtml(issue.message)}</div>
            <div class="issue-footer">
                <span class="issue-server">🖥️ ${this.escapeHtml(issue.serverName || 'Unknown')}</span>
                <span>📄 ${this.escapeHtml(issue.fileName)}:${issue.lineNumber}</span>
                <span>🕐 ${issue.detectedAt}</span>
            </div>
        `;
        
        el.addEventListener('click', () => this.showIssueDetail(issue.id));
        
        return el;
    }
    
    showIssueDetail(id) {
        const issue = this.issues.find(i => i.id === id);
        if (!issue) return;
        
        this.selectedIssue = issue;
        
        document.getElementById('modalTitle').textContent = issue.issueType;
        document.getElementById('modalBody').innerHTML = `
            <div class="detail-row">
                <div class="detail-label">Severity</div>
                <div class="detail-value">
                    <span class="issue-badge badge-${issue.severity}">${issue.severity}</span>
                </div>
            </div>
            <div class="detail-row">
                <div class="detail-label">Server</div>
                <div class="detail-value">${this.escapeHtml(issue.serverName || 'Unknown')}</div>
            </div>
            <div class="detail-row">
                <div class="detail-label">File</div>
                <div class="detail-value">${this.escapeHtml(issue.fileName)}:${issue.lineNumber}</div>
            </div>
            <div class="detail-row">
                <div class="detail-label">Detected At</div>
                <div class="detail-value">${issue.detectedAt}</div>
            </div>
            <div class="detail-row">
                <div class="detail-label">Message</div>
                <div class="detail-value">${this.escapeHtml(issue.message)}</div>
            </div>
            <div class="detail-row">
                <div class="detail-label">Stack Trace</div>
                <pre class="stack-trace">${this.escapeHtml(issue.fullStackTrace)}</pre>
            </div>
        `;
        
        document.getElementById('acknowledgeBtn').textContent = 
            issue.acknowledged ? 'Acknowledged ✓' : 'Acknowledge';
        
        document.getElementById('issueModal').classList.add('active');
    }
    
    closeModal() {
        document.getElementById('issueModal').classList.remove('active');
        this.selectedIssue = null;
    }
    
    async acknowledgeIssue() {
        if (!this.selectedIssue) return;
        
        try {
            const response = await fetch(`/api/issues/${this.selectedIssue.id}/acknowledge`, {
                method: 'POST'
            });
            
            if (response.ok) {
                this.selectedIssue.acknowledged = true;
                document.getElementById('acknowledgeBtn').textContent = 'Acknowledged ✓';
                this.renderIssues();
            }
        } catch (error) {
            console.error('Error acknowledging issue:', error);
        }
    }
    
    copyStackTrace() {
        if (!this.selectedIssue) return;
        
        navigator.clipboard.writeText(this.selectedIssue.fullStackTrace).then(() => {
            const btn = document.getElementById('copyStackTrace');
            const original = btn.textContent;
            btn.textContent = 'Copied! ✓';
            setTimeout(() => btn.textContent = original, 2000);
        });
    }
    
    // Stats display
    displayStats(stats) {
        document.getElementById('statTotal').textContent = stats.totalIssues || 0;
        document.getElementById('statCritical').textContent = stats.criticalCount || 0;
        document.getElementById('statException').textContent = stats.exceptionCount || 0;
        document.getElementById('statError').textContent = stats.errorCount || 0;
        document.getElementById('statWarning').textContent = stats.warningCount || 0;
        document.getElementById('statRate').textContent = 
            (stats.issueRatePerMinute || 0).toFixed(1);
    }
    
    updateStats() {
        this.loadStats();
    }
    
    // Analysis display
    displayAnalysis(analysis) {
        // Top exception types
        const exceptionsEl = document.getElementById('topExceptions');
        if (analysis.severityDistribution) {
            exceptionsEl.innerHTML = Object.entries(analysis.severityDistribution)
                .map(([name, stats]) => `
                    <div class="analysis-item">
                        <span class="analysis-item-name">${this.escapeHtml(name)}</span>
                        <span class="analysis-item-count">${stats.count} (${stats.percentage}%)</span>
                    </div>
                `).join('');
        }
        
        // Server health
        const healthEl = document.getElementById('serverHealth');
        if (analysis.serverHealthScores) {
            healthEl.innerHTML = Object.entries(analysis.serverHealthScores)
                .map(([server, score]) => {
                    let barClass = score >= 70 ? '' : score >= 40 ? 'medium' : 'low';
                    return `
                        <div class="health-item">
                            <div class="health-item-name">${this.escapeHtml(server)}</div>
                            <div class="health-bar">
                                <div class="health-bar-fill ${barClass}" style="width: ${score}%"></div>
                            </div>
                        </div>
                    `;
                }).join('');
        }
        
        // Recurring issues
        const recurringEl = document.getElementById('recurringIssues');
        if (analysis.recurringIssues && analysis.recurringIssues.length > 0) {
            recurringEl.innerHTML = analysis.recurringIssues.slice(0, 5)
                .map(ri => `
                    <div class="analysis-item">
                        <span class="analysis-item-name" title="${this.escapeHtml(ri.pattern)}">
                            ${this.escapeHtml(ri.pattern.substring(0, 40))}...
                        </span>
                        <span class="analysis-item-count">${ri.occurrences}x</span>
                    </div>
                `).join('');
        } else {
            recurringEl.innerHTML = '<div class="empty-state small"><p>No recurring issues</p></div>';
        }
        
        // Most affected files
        const filesEl = document.getElementById('affectedFiles');
        if (analysis.mostAffectedFiles) {
            filesEl.innerHTML = Object.entries(analysis.mostAffectedFiles || {})
                .slice(0, 5)
                .map(([file, count]) => `
                    <div class="analysis-item">
                        <span class="analysis-item-name">${this.escapeHtml(file)}</span>
                        <span class="analysis-item-count">${count}</span>
                    </div>
                `).join('') || '<div class="empty-state small"><p>No data</p></div>';
        }
    }
    
    displayAnomalies(anomalies) {
        const el = document.getElementById('anomaliesList');
        
        if (!anomalies || anomalies.length === 0) {
            el.innerHTML = `
                <div class="empty-state small">
                    <p>No anomalies detected</p>
                    <p class="empty-hint">Anomalies will appear when unusual patterns are detected</p>
                </div>
            `;
            return;
        }
        
        el.innerHTML = anomalies.map(a => `
            <div class="anomaly-item severity-${a.severity}">
                <div class="anomaly-header">
                    <span class="anomaly-type">${a.type}</span>
                    <span class="issue-badge badge-${a.severity === 'HIGH' ? 'ERROR' : 'WARNING'}">${a.severity}</span>
                </div>
                <div class="anomaly-description">${this.escapeHtml(a.description)}</div>
                ${a.affectedServers ? `<div class="anomaly-time">Servers: ${a.affectedServers.join(', ')}</div>` : ''}
            </div>
        `).join('');
    }
    
    // Filters
    filterBySeverity(severity) {
        this.filters.severity = severity;
        this.pagination.offset = 0;
        this.loadIssues();
    }
    
    filterByServer(server) {
        this.filters.server = server;
        this.pagination.offset = 0;
        this.loadIssues();
    }
    
    filterByDateFrom(date) {
        this.filters.dateFrom = date;
        this.pagination.offset = 0;
        this.loadIssues();
    }
    
    filterByDateTo(date) {
        this.filters.dateTo = date;
        this.pagination.offset = 0;
        this.loadIssues();
    }
    
    setQuickDateFilter(preset) {
        const today = new Date();
        const toDate = today.toISOString().split('T')[0];
        let fromDate;
        
        switch (preset) {
            case 'today':
                fromDate = toDate;
                break;
            case '7d':
                // Last 7 days including today: subtract 6 days
                const week = new Date(today);
                week.setDate(week.getDate() - 6);
                fromDate = week.toISOString().split('T')[0];
                break;
            case '30d':
                // Last 30 days including today: subtract 29 days
                const month = new Date(today);
                month.setDate(month.getDate() - 29);
                fromDate = month.toISOString().split('T')[0];
                break;
            default:
                return;
        }
        
        document.getElementById('dateFromFilter').value = fromDate;
        document.getElementById('dateToFilter').value = toDate;
        this.filters.dateFrom = fromDate;
        this.filters.dateTo = toDate;
        this.pagination.offset = 0;
        this.loadIssues();
    }
    
    clearFilters() {
        this.filters = {
            severity: '',
            server: '',
            dateFrom: '',
            dateTo: ''
        };
        
        document.getElementById('severityFilter').value = '';
        document.getElementById('serverFilter').value = '';
        document.getElementById('dateFromFilter').value = '';
        document.getElementById('dateToFilter').value = '';
        
        this.pagination.offset = 0;
        this.loadIssues();
    }
    
    updateServerFilter(servers) {
        const select = document.getElementById('serverFilter');
        const current = select.value;
        
        select.innerHTML = '<option value="">All Servers</option>' +
            servers.map(s => `<option value="${this.escapeHtml(s.name)}">${this.escapeHtml(s.name)} (${s.issueCount})</option>`).join('');
        
        select.value = current;
    }
    
    // Export functionality
    showExportModal() {
        document.getElementById('exportCount').textContent = this.pagination.totalFiltered;
        document.getElementById('exportModal').classList.add('active');
    }
    
    closeExportModal() {
        document.getElementById('exportModal').classList.remove('active');
    }
    
    exportIssues() {
        const format = document.querySelector('input[name="exportFormat"]:checked').value;
        
        const params = new URLSearchParams({ format });
        if (this.filters.severity) params.append('severity', this.filters.severity);
        if (this.filters.server) params.append('server', this.filters.server);
        if (this.filters.dateFrom) params.append('from', this.filters.dateFrom);
        if (this.filters.dateTo) params.append('to', this.filters.dateTo);
        
        const url = `/api/export?${params}`;
        
        // Create download link
        const a = document.createElement('a');
        a.href = url;
        a.download = `log-issues-export.${format}`;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        
        this.closeExportModal();
    }
    
    // Charts
    initCharts() {
        const isDark = document.documentElement.getAttribute('data-theme') === 'dark';
        const textColor = isDark ? '#94a3b8' : '#64748b';
        const gridColor = isDark ? '#334155' : '#e2e8f0';
        
        Chart.defaults.color = textColor;
        Chart.defaults.borderColor = gridColor;
        
        // Trend chart
        const trendCtx = document.getElementById('trendChart').getContext('2d');
        this.charts.trend = new Chart(trendCtx, {
            type: 'line',
            data: {
                labels: [],
                datasets: [{
                    label: 'Issues',
                    data: [],
                    borderColor: '#3b82f6',
                    backgroundColor: 'rgba(59, 130, 246, 0.1)',
                    fill: true,
                    tension: 0.4
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                    legend: { display: false }
                },
                scales: {
                    y: {
                        beginAtZero: true,
                        ticks: { stepSize: 1 }
                    }
                }
            }
        });
        
        // Severity chart
        const severityCtx = document.getElementById('severityChart').getContext('2d');
        this.charts.severity = new Chart(severityCtx, {
            type: 'doughnut',
            data: {
                labels: ['Critical', 'Exception', 'Error', 'Warning'],
                datasets: [{
                    data: [0, 0, 0, 0],
                    backgroundColor: ['#dc2626', '#f43f5e', '#ef4444', '#f59e0b']
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                    legend: {
                        position: 'bottom',
                        labels: { boxWidth: 12 }
                    }
                }
            }
        });
        
        // Server chart
        const serverCtx = document.getElementById('serverChart').getContext('2d');
        this.charts.server = new Chart(serverCtx, {
            type: 'bar',
            data: {
                labels: [],
                datasets: [{
                    label: 'Issues',
                    data: [],
                    backgroundColor: '#3b82f6'
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                    legend: { display: false }
                },
                scales: {
                    y: {
                        beginAtZero: true,
                        ticks: { stepSize: 1 }
                    }
                }
            }
        });
    }
    
    updateCharts(stats) {
        // Update trend chart
        if (stats.recentTrend) {
            this.charts.trend.data.labels = Object.keys(stats.recentTrend);
            this.charts.trend.data.datasets[0].data = Object.values(stats.recentTrend);
            this.charts.trend.update('none');
        }
        
        // Update severity chart
        this.charts.severity.data.datasets[0].data = [
            stats.criticalCount || 0,
            stats.exceptionCount || 0,
            stats.errorCount || 0,
            stats.warningCount || 0
        ];
        this.charts.severity.update('none');
        
        // Update server chart
        if (stats.serverBreakdown) {
            this.charts.server.data.labels = Object.keys(stats.serverBreakdown);
            this.charts.server.data.datasets[0].data = Object.values(stats.serverBreakdown);
            this.charts.server.update('none');
        }
    }
    
    updateChartColors() {
        const isDark = document.documentElement.getAttribute('data-theme') === 'dark';
        const textColor = isDark ? '#94a3b8' : '#64748b';
        const gridColor = isDark ? '#334155' : '#e2e8f0';
        
        Chart.defaults.color = textColor;
        Chart.defaults.borderColor = gridColor;
        
        Object.values(this.charts).forEach(chart => {
            chart.options.scales && Object.values(chart.options.scales).forEach(scale => {
                scale.grid = scale.grid || {};
                scale.grid.color = gridColor;
            });
            chart.update();
        });
    }
    
    // Actions
    async refresh() {
        const btn = document.getElementById('refreshBtn');
        btn.textContent = '⏳';
        
        await this.loadInitialData();
        
        btn.textContent = '🔄';
    }
    
    async clearAll() {
        if (!confirm('Are you sure you want to clear all issues?')) return;
        
        try {
            await fetch('/api/issues/clear', { method: 'POST' });
            this.issues = [];
            this.renderIssues();
            this.loadStats();
        } catch (error) {
            console.error('Error clearing issues:', error);
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

// Initialize the dashboard when DOM is ready
document.addEventListener('DOMContentLoaded', () => {
    window.dashboard = new LogDashboard();
});
