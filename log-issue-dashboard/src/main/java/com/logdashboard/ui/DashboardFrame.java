package com.logdashboard.ui;

import com.logdashboard.config.DashboardConfig;
import com.logdashboard.model.LogIssue;
import com.logdashboard.model.LogIssue.Severity;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Main dashboard UI frame for displaying log issues.
 */
public class DashboardFrame extends JFrame {
    
    private final DashboardConfig config;
    private final DefaultTableModel tableModel;
    private final JTable issueTable;
    private final JTextArea detailsArea;
    private JLabel statusLabel;
    private JLabel countLabel;
    private final Queue<LogIssue> issueQueue;
    private final List<LogIssue> allIssues;
    private final Map<String, LogIssue> issueMap;
    
    private JCheckBox showErrors;
    private JCheckBox showWarnings;
    private JCheckBox showExceptions;
    private JTextField searchField;
    private TableRowSorter<DefaultTableModel> sorter;
    
    private javax.swing.Timer uiUpdateTimer;
    
    public DashboardFrame(DashboardConfig config) {
        this.config = config;
        this.issueQueue = new ConcurrentLinkedQueue<>();
        this.allIssues = Collections.synchronizedList(new ArrayList<>());
        this.issueMap = new LinkedHashMap<>();
        
        setTitle(config.getWindowTitle());
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1200, 800);
        setLocationRelativeTo(null);
        
        // Set look and feel
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // Use default
        }
        
        // Create main panel
        JPanel mainPanel = new JPanel(new BorderLayout(5, 5));
        mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
        
        // Create toolbar
        JPanel toolbarPanel = createToolbar();
        mainPanel.add(toolbarPanel, BorderLayout.NORTH);
        
        // Create split pane for table and details
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        splitPane.setResizeWeight(0.7);
        
        // Create table
        String[] columns = {"Time", "Server", "Severity", "Type", "File", "Line", "Message"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        
        issueTable = new JTable(tableModel);
        issueTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        issueTable.setRowHeight(25);
        issueTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        issueTable.getTableHeader().setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        
        // Set column widths
        issueTable.getColumnModel().getColumn(0).setPreferredWidth(140);  // Time
        issueTable.getColumnModel().getColumn(1).setPreferredWidth(100);  // Server
        issueTable.getColumnModel().getColumn(2).setPreferredWidth(80);   // Severity
        issueTable.getColumnModel().getColumn(3).setPreferredWidth(100);  // Type
        issueTable.getColumnModel().getColumn(4).setPreferredWidth(150);  // File
        issueTable.getColumnModel().getColumn(5).setPreferredWidth(50);   // Line
        issueTable.getColumnModel().getColumn(6).setPreferredWidth(400);  // Message
        
        // Custom renderer for severity coloring
        issueTable.getColumnModel().getColumn(2).setCellRenderer(new SeverityRenderer());
        
        // Custom renderer for server column
        issueTable.getColumnModel().getColumn(1).setCellRenderer(new ServerRenderer());
        
        // Add row sorter
        sorter = new TableRowSorter<>(tableModel);
        issueTable.setRowSorter(sorter);
        
        // Selection listener
        issueTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showSelectedIssueDetails();
            }
        });
        
        // Double-click to acknowledge
        issueTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    acknowledgeSelectedIssue();
                }
            }
        });
        
        JScrollPane tableScrollPane = new JScrollPane(issueTable);
        tableScrollPane.setBorder(BorderFactory.createTitledBorder("Issues"));
        splitPane.setTopComponent(tableScrollPane);
        
        // Create details panel
        detailsArea = new JTextArea();
        detailsArea.setEditable(false);
        detailsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        detailsArea.setBackground(new Color(45, 45, 45));
        detailsArea.setForeground(Color.WHITE);
        detailsArea.setCaretColor(Color.WHITE);
        
        JScrollPane detailsScrollPane = new JScrollPane(detailsArea);
        detailsScrollPane.setBorder(BorderFactory.createTitledBorder("Stack Trace / Details"));
        splitPane.setBottomComponent(detailsScrollPane);
        
        mainPanel.add(splitPane, BorderLayout.CENTER);
        
        // Create status bar
        JPanel statusBar = createStatusBar();
        mainPanel.add(statusBar, BorderLayout.SOUTH);
        
        setContentPane(mainPanel);
        
        // Start UI update timer
        startUIUpdateTimer();
        
        // Add window close handler
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (uiUpdateTimer != null) {
                    uiUpdateTimer.stop();
                }
            }
        });
    }
    
    private JPanel createToolbar() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        toolbar.setBorder(BorderFactory.createEtchedBorder());
        
        // Filter checkboxes
        showErrors = new JCheckBox("Errors", true);
        showWarnings = new JCheckBox("Warnings", true);
        showExceptions = new JCheckBox("Exceptions", true);
        
        ActionListener filterListener = e -> applyFilters();
        showErrors.addActionListener(filterListener);
        showWarnings.addActionListener(filterListener);
        showExceptions.addActionListener(filterListener);
        
        toolbar.add(new JLabel("Show:"));
        toolbar.add(showErrors);
        toolbar.add(showWarnings);
        toolbar.add(showExceptions);
        
        toolbar.add(new JSeparator(JSeparator.VERTICAL));
        
        // Search field
        toolbar.add(new JLabel("Search:"));
        searchField = new JTextField(20);
        searchField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyReleased(KeyEvent e) {
                applyFilters();
            }
        });
        toolbar.add(searchField);
        
        toolbar.add(new JSeparator(JSeparator.VERTICAL));
        
        // Action buttons
        JButton clearButton = new JButton("Clear All");
        clearButton.addActionListener(e -> clearAllIssues());
        toolbar.add(clearButton);
        
        JButton refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(e -> refreshView());
        toolbar.add(refreshButton);
        
        JButton acknowledgeButton = new JButton("Acknowledge Selected");
        acknowledgeButton.addActionListener(e -> acknowledgeSelectedIssue());
        toolbar.add(acknowledgeButton);
        
        return toolbar;
    }
    
    private JPanel createStatusBar() {
        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBorder(BorderFactory.createEtchedBorder());
        
        statusLabel = new JLabel(" Ready");
        statusLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        
        countLabel = new JLabel("Issues: 0 ");
        countLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        
        statusBar.add(statusLabel, BorderLayout.WEST);
        statusBar.add(countLabel, BorderLayout.EAST);
        
        return statusBar;
    }
    
    /**
     * Adds a new issue to the dashboard (thread-safe).
     */
    public void addIssue(LogIssue issue) {
        issueQueue.offer(issue);
    }
    
    /**
     * Updates the status message (thread-safe).
     */
    public void updateStatus(String status) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(" " + status);
        });
    }
    
    /**
     * Starts the timer that periodically updates the UI with new issues.
     */
    private void startUIUpdateTimer() {
        uiUpdateTimer = new javax.swing.Timer(200, e -> processQueuedIssues());
        uiUpdateTimer.start();
    }
    
    /**
     * Processes queued issues and adds them to the table.
     */
    private void processQueuedIssues() {
        List<LogIssue> newIssues = new ArrayList<>();
        LogIssue issue;
        while ((issue = issueQueue.poll()) != null) {
            newIssues.add(issue);
        }
        
        if (!newIssues.isEmpty()) {
            for (LogIssue newIssue : newIssues) {
                allIssues.add(newIssue);
                issueMap.put(newIssue.getId(), newIssue);
                addIssueToTable(newIssue);
                
                // Limit issues in memory
                if (allIssues.size() > config.getMaxIssuesDisplayed()) {
                    LogIssue oldest = allIssues.remove(0);
                    issueMap.remove(oldest.getId());
                    tableModel.removeRow(0);
                }
            }
            
            updateCountLabel();
            
            // Auto-scroll to latest
            if (issueTable.getRowCount() > 0) {
                issueTable.scrollRectToVisible(
                    issueTable.getCellRect(issueTable.getRowCount() - 1, 0, true)
                );
            }
        }
    }
    
    private void addIssueToTable(LogIssue issue) {
        Object[] row = {
            issue.getFormattedTime(),
            issue.getServerName() != null ? issue.getServerName() : "-",
            issue.getSeverity().getDisplayName(),
            issue.getIssueType(),
            issue.getFileName(),
            issue.getLineNumber(),
            truncateMessage(issue.getMessage())
        };
        tableModel.addRow(row);
    }
    
    private String truncateMessage(String message) {
        if (message.length() > 150) {
            return message.substring(0, 147) + "...";
        }
        return message;
    }
    
    private void showSelectedIssueDetails() {
        int viewRow = issueTable.getSelectedRow();
        if (viewRow < 0) {
            detailsArea.setText("");
            return;
        }
        
        int modelRow = issueTable.convertRowIndexToModel(viewRow);
        if (modelRow >= 0 && modelRow < allIssues.size()) {
            LogIssue issue = allIssues.get(modelRow);
            StringBuilder details = new StringBuilder();
            details.append("=== Issue Details ===\n\n");
            details.append("Time: ").append(issue.getFormattedTime()).append("\n");
            if (issue.getServerName() != null) {
                details.append("Server: ").append(issue.getServerName()).append("\n");
            }
            details.append("File: ").append(issue.getFileName()).append("\n");
            details.append("Line: ").append(issue.getLineNumber()).append("\n");
            details.append("Type: ").append(issue.getIssueType()).append("\n");
            details.append("Severity: ").append(issue.getSeverity().getDisplayName()).append("\n");
            details.append("Acknowledged: ").append(issue.isAcknowledged() ? "Yes" : "No").append("\n");
            details.append("\n=== Full Stack Trace ===\n\n");
            details.append(issue.getFullStackTrace());
            detailsArea.setText(details.toString());
            detailsArea.setCaretPosition(0);
        }
    }
    
    private void acknowledgeSelectedIssue() {
        int viewRow = issueTable.getSelectedRow();
        if (viewRow < 0) {
            return;
        }
        
        int modelRow = issueTable.convertRowIndexToModel(viewRow);
        if (modelRow >= 0 && modelRow < allIssues.size()) {
            LogIssue issue = allIssues.get(modelRow);
            issue.setAcknowledged(true);
            showSelectedIssueDetails();
            JOptionPane.showMessageDialog(this, 
                "Issue acknowledged", 
                "Acknowledged", 
                JOptionPane.INFORMATION_MESSAGE);
        }
    }
    
    private void clearAllIssues() {
        int confirm = JOptionPane.showConfirmDialog(this,
            "Are you sure you want to clear all issues?",
            "Clear Issues",
            JOptionPane.YES_NO_OPTION);
        
        if (confirm == JOptionPane.YES_OPTION) {
            allIssues.clear();
            issueMap.clear();
            tableModel.setRowCount(0);
            detailsArea.setText("");
            updateCountLabel();
        }
    }
    
    private void refreshView() {
        applyFilters();
        updateCountLabel();
    }
    
    private void applyFilters() {
        List<RowFilter<Object, Object>> filters = new ArrayList<>();
        
        // Severity filters (column index 2 now, since Server is column 1)
        List<String> allowedSeverities = new ArrayList<>();
        if (showErrors.isSelected()) allowedSeverities.add("Error");
        if (showWarnings.isSelected()) allowedSeverities.add("Warning");
        if (showExceptions.isSelected()) allowedSeverities.add("Exception");
        
        if (!allowedSeverities.isEmpty()) {
            RowFilter<Object, Object> severityFilter = new RowFilter<Object, Object>() {
                @Override
                public boolean include(Entry<?, ?> entry) {
                    String severity = entry.getStringValue(2);  // Column 2 is now Severity
                    return allowedSeverities.contains(severity);
                }
            };
            filters.add(severityFilter);
        }
        
        // Text search filter
        String searchText = searchField.getText().trim();
        if (!searchText.isEmpty()) {
            try {
                filters.add(RowFilter.regexFilter("(?i)" + searchText));
            } catch (Exception e) {
                // Invalid regex, use plain text
                filters.add(RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(searchText)));
            }
        }
        
        if (filters.isEmpty()) {
            sorter.setRowFilter(null);
        } else {
            sorter.setRowFilter(RowFilter.andFilter(filters));
        }
    }
    
    private void updateCountLabel() {
        int total = allIssues.size();
        int visible = issueTable.getRowCount();
        countLabel.setText(String.format("Showing: %d / Total: %d ", visible, total));
    }
    
    /**
     * Custom renderer for the severity column.
     */
    private static class SeverityRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            
            String severity = (String) value;
            
            if (!isSelected) {
                switch (severity) {
                    case "Exception":
                        c.setBackground(new Color(255, 200, 200));
                        c.setForeground(Color.RED.darker());
                        break;
                    case "Error":
                        c.setBackground(new Color(255, 220, 220));
                        c.setForeground(Color.RED);
                        break;
                    case "Warning":
                        c.setBackground(new Color(255, 255, 200));
                        c.setForeground(new Color(180, 120, 0));
                        break;
                    default:
                        c.setBackground(Color.WHITE);
                        c.setForeground(Color.BLACK);
                }
            }
            
            setHorizontalAlignment(CENTER);
            return c;
        }
    }
    
    /**
     * Custom renderer for the server column with distinct colors.
     */
    private static class ServerRenderer extends DefaultTableCellRenderer {
        private static final Color[] SERVER_COLORS = {
            new Color(200, 220, 255),  // Light blue
            new Color(200, 255, 200),  // Light green
            new Color(255, 220, 200),  // Light orange
            new Color(220, 200, 255),  // Light purple
            new Color(255, 255, 200),  // Light yellow
            new Color(200, 255, 255),  // Light cyan
            new Color(255, 200, 255),  // Light magenta
        };
        
        private final Map<String, Color> serverColorMap = new HashMap<>();
        private int colorIndex = 0;
        
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            
            String serverName = (String) value;
            
            if (!isSelected && serverName != null && !"-".equals(serverName)) {
                Color serverColor = serverColorMap.computeIfAbsent(serverName, k -> {
                    Color color = SERVER_COLORS[colorIndex % SERVER_COLORS.length];
                    colorIndex++;
                    return color;
                });
                c.setBackground(serverColor);
                c.setForeground(Color.BLACK);
            } else if (!isSelected) {
                c.setBackground(Color.WHITE);
                c.setForeground(Color.GRAY);
            }
            
            setHorizontalAlignment(CENTER);
            setFont(getFont().deriveFont(Font.BOLD));
            return c;
        }
    }
}
