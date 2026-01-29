package com.logdashboard.store;

import com.logdashboard.model.IssueSolution;
import com.logdashboard.model.IssueSolution.Status;
import com.logdashboard.model.SolutionMatch;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * H2 database implementation of the SolutionStore interface.
 * Provides persistent storage for issue solutions and match tracking.
 */
public class H2SolutionStore implements SolutionStore {
    
    private final DatabaseManager dbManager;
    
    public H2SolutionStore(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }
    
    @Override
    public IssueSolution addSolution(IssueSolution solution) {
        String sql = "INSERT INTO issue_solutions (id, issue_pattern, message_pattern, stack_pattern, " +
                     "solution_title, solution_description, created_by, created_at, updated_at, " +
                     "upvotes, downvotes, usage_count, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        
        try (PreparedStatement stmt = dbManager.getConnection().prepareStatement(sql)) {
            stmt.setString(1, solution.getId());
            stmt.setString(2, solution.getIssuePattern());
            stmt.setString(3, solution.getMessagePattern());
            stmt.setString(4, solution.getStackPattern());
            stmt.setString(5, solution.getSolutionTitle());
            stmt.setString(6, solution.getSolutionDescription());
            stmt.setString(7, solution.getCreatedBy());
            stmt.setTimestamp(8, Timestamp.valueOf(solution.getCreatedAt()));
            stmt.setTimestamp(9, Timestamp.valueOf(solution.getUpdatedAt()));
            stmt.setInt(10, solution.getUpvotes());
            stmt.setInt(11, solution.getDownvotes());
            stmt.setInt(12, solution.getUsageCount());
            stmt.setString(13, solution.getStatus().name());
            
            stmt.executeUpdate();
            System.out.println("Added solution: " + solution.getSolutionTitle());
            return solution;
        } catch (SQLException e) {
            System.err.println("Error adding solution: " + e.getMessage());
            return null;
        }
    }
    
    @Override
    public boolean updateSolution(IssueSolution solution) {
        String sql = "UPDATE issue_solutions SET issue_pattern = ?, message_pattern = ?, stack_pattern = ?, " +
                     "solution_title = ?, solution_description = ?, updated_at = ?, status = ? WHERE id = ?";
        
        try (PreparedStatement stmt = dbManager.getConnection().prepareStatement(sql)) {
            stmt.setString(1, solution.getIssuePattern());
            stmt.setString(2, solution.getMessagePattern());
            stmt.setString(3, solution.getStackPattern());
            stmt.setString(4, solution.getSolutionTitle());
            stmt.setString(5, solution.getSolutionDescription());
            stmt.setTimestamp(6, Timestamp.valueOf(LocalDateTime.now()));
            stmt.setString(7, solution.getStatus().name());
            stmt.setString(8, solution.getId());
            
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error updating solution: " + e.getMessage());
            return false;
        }
    }
    
    @Override
    public Optional<IssueSolution> getSolutionById(String id) {
        String sql = "SELECT * FROM issue_solutions WHERE id = ?";
        
        try (PreparedStatement stmt = dbManager.getConnection().prepareStatement(sql)) {
            stmt.setString(1, id);
            ResultSet rs = stmt.executeQuery();
            
            if (rs.next()) {
                return Optional.of(resultSetToSolution(rs));
            }
        } catch (SQLException e) {
            System.err.println("Error getting solution by id: " + e.getMessage());
        }
        return Optional.empty();
    }
    
    @Override
    public List<IssueSolution> getAllActiveSolutions() {
        return getSolutions(0, Integer.MAX_VALUE, false);
    }
    
    @Override
    public List<IssueSolution> getSolutions(int offset, int limit, boolean includeArchived) {
        String sql = includeArchived 
            ? "SELECT * FROM issue_solutions ORDER BY upvotes DESC, created_at DESC LIMIT ? OFFSET ?"
            : "SELECT * FROM issue_solutions WHERE status = 'ACTIVE' ORDER BY upvotes DESC, created_at DESC LIMIT ? OFFSET ?";
        
        List<IssueSolution> solutions = new ArrayList<>();
        try (PreparedStatement stmt = dbManager.getConnection().prepareStatement(sql)) {
            stmt.setInt(1, limit);
            stmt.setInt(2, offset);
            ResultSet rs = stmt.executeQuery();
            
            while (rs.next()) {
                solutions.add(resultSetToSolution(rs));
            }
        } catch (SQLException e) {
            System.err.println("Error getting solutions: " + e.getMessage());
        }
        return solutions;
    }
    
    @Override
    public List<IssueSolution> getSolutionsByPattern(String issueType) {
        // Match solutions where the issue pattern matches or is contained in the issue type
        String sql = "SELECT * FROM issue_solutions WHERE status = 'ACTIVE' AND " +
                     "(LOWER(issue_pattern) = LOWER(?) OR " +
                     " LOWER(?) LIKE CONCAT('%', LOWER(issue_pattern), '%') OR " +
                     " LOWER(issue_pattern) LIKE CONCAT('%', LOWER(?), '%')) " +
                     "ORDER BY upvotes DESC, usage_count DESC";
        
        List<IssueSolution> solutions = new ArrayList<>();
        try (PreparedStatement stmt = dbManager.getConnection().prepareStatement(sql)) {
            stmt.setString(1, issueType);
            stmt.setString(2, issueType);
            stmt.setString(3, issueType);
            ResultSet rs = stmt.executeQuery();
            
            while (rs.next()) {
                solutions.add(resultSetToSolution(rs));
            }
        } catch (SQLException e) {
            System.err.println("Error getting solutions by pattern: " + e.getMessage());
        }
        return solutions;
    }
    
    @Override
    public List<IssueSolution> searchSolutions(String keyword) {
        String sql = "SELECT * FROM issue_solutions WHERE status = 'ACTIVE' AND " +
                     "(LOWER(solution_title) LIKE ? OR LOWER(solution_description) LIKE ? OR LOWER(issue_pattern) LIKE ?) " +
                     "ORDER BY upvotes DESC, created_at DESC";
        
        List<IssueSolution> solutions = new ArrayList<>();
        String searchPattern = "%" + keyword.toLowerCase() + "%";
        
        try (PreparedStatement stmt = dbManager.getConnection().prepareStatement(sql)) {
            stmt.setString(1, searchPattern);
            stmt.setString(2, searchPattern);
            stmt.setString(3, searchPattern);
            ResultSet rs = stmt.executeQuery();
            
            while (rs.next()) {
                solutions.add(resultSetToSolution(rs));
            }
        } catch (SQLException e) {
            System.err.println("Error searching solutions: " + e.getMessage());
        }
        return solutions;
    }
    
    @Override
    public boolean upvoteSolution(String solutionId) {
        String sql = "UPDATE issue_solutions SET upvotes = upvotes + 1, updated_at = ? WHERE id = ?";
        
        try (PreparedStatement stmt = dbManager.getConnection().prepareStatement(sql)) {
            stmt.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now()));
            stmt.setString(2, solutionId);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error upvoting solution: " + e.getMessage());
            return false;
        }
    }
    
    @Override
    public boolean downvoteSolution(String solutionId) {
        String sql = "UPDATE issue_solutions SET downvotes = downvotes + 1, updated_at = ? WHERE id = ?";
        
        try (PreparedStatement stmt = dbManager.getConnection().prepareStatement(sql)) {
            stmt.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now()));
            stmt.setString(2, solutionId);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error downvoting solution: " + e.getMessage());
            return false;
        }
    }
    
    @Override
    public boolean incrementUsageCount(String solutionId) {
        String sql = "UPDATE issue_solutions SET usage_count = usage_count + 1 WHERE id = ?";
        
        try (PreparedStatement stmt = dbManager.getConnection().prepareStatement(sql)) {
            stmt.setString(1, solutionId);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error incrementing usage count: " + e.getMessage());
            return false;
        }
    }
    
    @Override
    public boolean archiveSolution(String solutionId) {
        String sql = "UPDATE issue_solutions SET status = 'ARCHIVED', updated_at = ? WHERE id = ?";
        
        try (PreparedStatement stmt = dbManager.getConnection().prepareStatement(sql)) {
            stmt.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now()));
            stmt.setString(2, solutionId);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error archiving solution: " + e.getMessage());
            return false;
        }
    }
    
    @Override
    public boolean deleteSolution(String solutionId) {
        String sql = "DELETE FROM issue_solutions WHERE id = ?";
        
        try (PreparedStatement stmt = dbManager.getConnection().prepareStatement(sql)) {
            stmt.setString(1, solutionId);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error deleting solution: " + e.getMessage());
            return false;
        }
    }
    
    @Override
    public int getActiveSolutionCount() {
        return getTotalSolutionCount(false);
    }
    
    @Override
    public int getTotalSolutionCount(boolean includeArchived) {
        String sql = includeArchived 
            ? "SELECT COUNT(*) FROM issue_solutions"
            : "SELECT COUNT(*) FROM issue_solutions WHERE status = 'ACTIVE'";
        
        try (Statement stmt = dbManager.getConnection().createStatement()) {
            ResultSet rs = stmt.executeQuery(sql);
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            System.err.println("Error getting solution count: " + e.getMessage());
        }
        return 0;
    }
    
    // Solution Match operations
    
    @Override
    public SolutionMatch recordMatch(SolutionMatch match) {
        String sql = "INSERT INTO solution_matches (id, solution_id, issue_id, match_score, matched_at, was_helpful, feedback_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?)";
        
        try (PreparedStatement stmt = dbManager.getConnection().prepareStatement(sql)) {
            stmt.setString(1, match.getId());
            stmt.setString(2, match.getSolutionId());
            stmt.setString(3, match.getIssueId());
            stmt.setInt(4, match.getMatchScore());
            stmt.setTimestamp(5, Timestamp.valueOf(match.getMatchedAt()));
            if (match.getWasHelpful() != null) {
                stmt.setBoolean(6, match.getWasHelpful());
            } else {
                stmt.setNull(6, Types.BOOLEAN);
            }
            if (match.getFeedbackAt() != null) {
                stmt.setTimestamp(7, Timestamp.valueOf(match.getFeedbackAt()));
            } else {
                stmt.setNull(7, Types.TIMESTAMP);
            }
            
            stmt.executeUpdate();
            return match;
        } catch (SQLException e) {
            System.err.println("Error recording match: " + e.getMessage());
            return null;
        }
    }
    
    @Override
    public boolean recordMatchFeedback(String matchId, boolean wasHelpful) {
        String sql = "UPDATE solution_matches SET was_helpful = ?, feedback_at = ? WHERE id = ?";
        
        try (PreparedStatement stmt = dbManager.getConnection().prepareStatement(sql)) {
            stmt.setBoolean(1, wasHelpful);
            stmt.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            stmt.setString(3, matchId);
            
            boolean updated = stmt.executeUpdate() > 0;
            
            // Also update the solution's vote count based on feedback
            if (updated) {
                Optional<SolutionMatch> match = getMatchById(matchId);
                if (match.isPresent()) {
                    if (wasHelpful) {
                        upvoteSolution(match.get().getSolutionId());
                    } else {
                        downvoteSolution(match.get().getSolutionId());
                    }
                }
            }
            
            return updated;
        } catch (SQLException e) {
            System.err.println("Error recording match feedback: " + e.getMessage());
            return false;
        }
    }
    
    @Override
    public Optional<SolutionMatch> getMatchById(String matchId) {
        String sql = "SELECT * FROM solution_matches WHERE id = ?";
        
        try (PreparedStatement stmt = dbManager.getConnection().prepareStatement(sql)) {
            stmt.setString(1, matchId);
            ResultSet rs = stmt.executeQuery();
            
            if (rs.next()) {
                return Optional.of(resultSetToMatch(rs));
            }
        } catch (SQLException e) {
            System.err.println("Error getting match by id: " + e.getMessage());
        }
        return Optional.empty();
    }
    
    @Override
    public List<SolutionMatch> getMatchesForSolution(String solutionId) {
        String sql = "SELECT * FROM solution_matches WHERE solution_id = ? ORDER BY matched_at DESC";
        
        List<SolutionMatch> matches = new ArrayList<>();
        try (PreparedStatement stmt = dbManager.getConnection().prepareStatement(sql)) {
            stmt.setString(1, solutionId);
            ResultSet rs = stmt.executeQuery();
            
            while (rs.next()) {
                matches.add(resultSetToMatch(rs));
            }
        } catch (SQLException e) {
            System.err.println("Error getting matches for solution: " + e.getMessage());
        }
        return matches;
    }
    
    @Override
    public SolutionStats getSolutionStats(String solutionId) {
        String sql = "SELECT " +
                     "COUNT(*) as total, " +
                     "SUM(CASE WHEN was_helpful = TRUE THEN 1 ELSE 0 END) as helpful, " +
                     "SUM(CASE WHEN was_helpful = FALSE THEN 1 ELSE 0 END) as not_helpful, " +
                     "SUM(CASE WHEN was_helpful IS NULL THEN 1 ELSE 0 END) as no_feedback " +
                     "FROM solution_matches WHERE solution_id = ?";
        
        try (PreparedStatement stmt = dbManager.getConnection().prepareStatement(sql)) {
            stmt.setString(1, solutionId);
            ResultSet rs = stmt.executeQuery();
            
            if (rs.next()) {
                return new SolutionStats(
                    rs.getInt("total"),
                    rs.getInt("helpful"),
                    rs.getInt("not_helpful"),
                    rs.getInt("no_feedback")
                );
            }
        } catch (SQLException e) {
            System.err.println("Error getting solution stats: " + e.getMessage());
        }
        return new SolutionStats(0, 0, 0, 0);
    }
    
    /**
     * Converts a ResultSet row to an IssueSolution object.
     */
    private IssueSolution resultSetToSolution(ResultSet rs) throws SQLException {
        Status status;
        try {
            status = Status.valueOf(rs.getString("status"));
        } catch (IllegalArgumentException e) {
            status = Status.ACTIVE;
        }
        
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        
        return new IssueSolution(
            rs.getString("id"),
            rs.getString("issue_pattern"),
            rs.getString("message_pattern"),
            rs.getString("stack_pattern"),
            rs.getString("solution_title"),
            rs.getString("solution_description"),
            rs.getString("created_by"),
            createdAt != null ? createdAt.toLocalDateTime() : LocalDateTime.now(),
            updatedAt != null ? updatedAt.toLocalDateTime() : LocalDateTime.now(),
            rs.getInt("upvotes"),
            rs.getInt("downvotes"),
            rs.getInt("usage_count"),
            status
        );
    }
    
    /**
     * Converts a ResultSet row to a SolutionMatch object.
     */
    private SolutionMatch resultSetToMatch(ResultSet rs) throws SQLException {
        Timestamp matchedAt = rs.getTimestamp("matched_at");
        Timestamp feedbackAt = rs.getTimestamp("feedback_at");
        
        Boolean wasHelpful = rs.getBoolean("was_helpful");
        if (rs.wasNull()) {
            wasHelpful = null;
        }
        
        return new SolutionMatch(
            rs.getString("id"),
            rs.getString("solution_id"),
            rs.getString("issue_id"),
            rs.getInt("match_score"),
            matchedAt != null ? matchedAt.toLocalDateTime() : LocalDateTime.now(),
            wasHelpful,
            feedbackAt != null ? feedbackAt.toLocalDateTime() : null
        );
    }
}
