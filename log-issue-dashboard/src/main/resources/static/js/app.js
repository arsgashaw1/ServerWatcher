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
            server: ''
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
        document.getElementById('severityFilter').addEventListener('change', (e) => this.filterBySeverity(e.target.value));
        document.getElementById('serverFilter').addEventListener('change', (e) => this.filterByServer(e.target.value));
        document.getElementById('modalClose').addEventListener('click', () => this.closeModal());
        document.getElementById('acknowledgeBtn').addEventListener('click', () => this.acknowledgeIssue());
        document.getElementById('copyStackTrace').addEventListener('click', () => this.copyStackTrace());
        
        // Close modal on outside click
        document.getElementById('issueModal').addEventListener('click', (e) => {
            if (e.target.id === 'issueModal') this.closeModal();
        });
        
        // Keyboard shortcuts
        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') this.closeModal();
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
                this.loadServers()
            ]);
        } catch (error) {
            console.error('Error loading initial data:', error);
        }
    }
    
    async loadIssues() {
        try {
            const response = await fetch('/api/issues?limit=100');
            const data = await response.json();
            this.issues = data.issues || [];
            this.renderIssues();
        } catch (error) {
            console.error('Error loading issues:', error);
        }
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
    
    // Issue handling
    addIssue(issue, isNew = false) {
        // Add to beginning of list
        this.issues.unshift(issue);
        
        // Limit issues
        if (this.issues.length > 500) {
            this.issues = this.issues.slice(0, 500);
        }
        
        // Re-render or add single item
        if (isNew) {
            this.prependIssueElement(issue);
        } else {
            this.renderIssues();
        }
    }
    
    prependIssueElement(issue) {
        const list = document.getElementById('issuesList');
        
        // Remove empty state if present
        const emptyState = list.querySelector('.empty-state');
        if (emptyState) {
            emptyState.remove();
        }
        
        // Check if it matches current filter
        if (this.filters.severity && issue.severity !== this.filters.severity) return;
        if (this.filters.server && issue.serverName !== this.filters.server) return;
        
        const el = this.createIssueElement(issue);
        el.classList.add('new');
        list.insertBefore(el, list.firstChild);
        
        // Remove animation class after animation completes
        setTimeout(() => el.classList.remove('new'), 300);
    }
    
    renderIssues() {
        const list = document.getElementById('issuesList');
        
        let filtered = this.issues;
        
        if (this.filters.severity) {
            filtered = filtered.filter(i => i.severity === this.filters.severity);
        }
        
        if (this.filters.server) {
            filtered = filtered.filter(i => i.serverName === this.filters.server);
        }
        
        if (filtered.length === 0) {
            list.innerHTML = `
                <div class="empty-state">
                    <div class="empty-icon">🔍</div>
                    <p>No issues detected yet</p>
                    <p class="empty-hint">Issues will appear here in real-time</p>
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
        this.renderIssues();
    }
    
    filterByServer(server) {
        this.filters.server = server;
        this.renderIssues();
    }
    
    updateServerFilter(servers) {
        const select = document.getElementById('serverFilter');
        const current = select.value;
        
        select.innerHTML = '<option value="">All Servers</option>' +
            servers.map(s => `<option value="${this.escapeHtml(s.name)}">${this.escapeHtml(s.name)} (${s.issueCount})</option>`).join('');
        
        select.value = current;
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
