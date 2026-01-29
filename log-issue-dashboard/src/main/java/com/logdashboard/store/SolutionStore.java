package com.logdashboard.store;

import com.logdashboard.model.IssueSolution;
import com.logdashboard.model.SolutionMatch;

import java.util.List;
import java.util.Optional;

/**
 * Interface for storing and retrieving issue solutions.
 */
public interface SolutionStore {
    
    /**
     * Adds a new solution to the store.
     * @param solution The solution to add
     * @return The added solution with generated ID
     */
    IssueSolution addSolution(IssueSolution solution);
    
    /**
     * Updates an existing solution.
     * @param solution The solution with updated fields
     * @return true if updated, false if not found
     */
    boolean updateSolution(IssueSolution solution);
    
    /**
     * Gets a solution by ID.
     * @param id The solution ID
     * @return Optional containing the solution if found
     */
    Optional<IssueSolution> getSolutionById(String id);
    
    /**
     * Gets all active solutions.
     * @return List of active solutions
     */
    List<IssueSolution> getAllActiveSolutions();
    
    /**
     * Gets all solutions with pagination.
     * @param offset Starting offset
     * @param limit Maximum number of solutions to return
     * @param includeArchived Whether to include archived/deprecated solutions
     * @return List of solutions
     */
    List<IssueSolution> getSolutions(int offset, int limit, boolean includeArchived);
    
    /**
     * Gets solutions matching an issue pattern.
     * @param issueType The issue type to match
     * @return List of solutions that might match
     */
    List<IssueSolution> getSolutionsByPattern(String issueType);
    
    /**
     * Searches solutions by keyword in title or description.
     * @param keyword The keyword to search for
     * @return List of matching solutions
     */
    List<IssueSolution> searchSolutions(String keyword);
    
    /**
     * Records an upvote for a solution.
     * @param solutionId The solution ID
     * @return true if recorded, false if solution not found
     */
    boolean upvoteSolution(String solutionId);
    
    /**
     * Records a downvote for a solution.
     * @param solutionId The solution ID
     * @return true if recorded, false if solution not found
     */
    boolean downvoteSolution(String solutionId);
    
    /**
     * Increments the usage count for a solution.
     * @param solutionId The solution ID
     * @return true if incremented, false if solution not found
     */
    boolean incrementUsageCount(String solutionId);
    
    /**
     * Archives a solution (soft delete).
     * @param solutionId The solution ID
     * @return true if archived, false if not found
     */
    boolean archiveSolution(String solutionId);
    
    /**
     * Permanently deletes a solution.
     * @param solutionId The solution ID
     * @return true if deleted, false if not found
     */
    boolean deleteSolution(String solutionId);
    
    /**
     * Gets the total count of active solutions.
     * @return Count of active solutions
     */
    int getActiveSolutionCount();
    
    /**
     * Gets the total count of all solutions.
     * @param includeArchived Whether to include archived/deprecated
     * @return Total count
     */
    int getTotalSolutionCount(boolean includeArchived);
    
    // Solution Match operations
    
    /**
     * Records a solution match (suggestion).
     * @param match The match to record
     * @return The recorded match
     */
    SolutionMatch recordMatch(SolutionMatch match);
    
    /**
     * Records user feedback on a match.
     * @param matchId The match ID
     * @param wasHelpful Whether the suggestion was helpful
     * @return true if recorded, false if match not found
     */
    boolean recordMatchFeedback(String matchId, boolean wasHelpful);
    
    /**
     * Gets match by ID.
     * @param matchId The match ID
     * @return Optional containing the match if found
     */
    Optional<SolutionMatch> getMatchById(String matchId);
    
    /**
     * Gets all matches for a solution (for analytics).
     * @param solutionId The solution ID
     * @return List of matches
     */
    List<SolutionMatch> getMatchesForSolution(String solutionId);
    
    /**
     * Gets statistics for a solution.
     * @param solutionId The solution ID
     * @return Statistics map containing helpfulCount, notHelpfulCount, totalMatches
     */
    SolutionStats getSolutionStats(String solutionId);
    
    /**
     * Container for solution statistics.
     */
    class SolutionStats {
        public final int totalMatches;
        public final int helpfulCount;
        public final int notHelpfulCount;
        public final int noFeedbackCount;
        
        public SolutionStats(int totalMatches, int helpfulCount, int notHelpfulCount, int noFeedbackCount) {
            this.totalMatches = totalMatches;
            this.helpfulCount = helpfulCount;
            this.notHelpfulCount = notHelpfulCount;
            this.noFeedbackCount = noFeedbackCount;
        }
        
        public double getHelpfulRate() {
            int withFeedback = helpfulCount + notHelpfulCount;
            if (withFeedback == 0) return 0;
            return Math.round((double) helpfulCount / withFeedback * 100 * 10) / 10.0;
        }
    }
}
