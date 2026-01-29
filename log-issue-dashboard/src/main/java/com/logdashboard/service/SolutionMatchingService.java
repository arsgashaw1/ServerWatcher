package com.logdashboard.service;

import com.logdashboard.model.IssueSolution;
import com.logdashboard.model.LogIssue;
import com.logdashboard.model.SolutionMatch;
import com.logdashboard.store.SolutionStore;

import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Service for matching solutions to issues based on various patterns.
 * Uses a multi-level scoring system to rank solution relevance.
 */
public class SolutionMatchingService {
    
    private final SolutionStore solutionStore;
    
    // Scoring weights
    private static final int EXACT_TYPE_MATCH_SCORE = 100;
    private static final int CONTAINS_TYPE_MATCH_SCORE = 70;
    private static final int PARTIAL_TYPE_MATCH_SCORE = 50;
    private static final int MESSAGE_PATTERN_MATCH_SCORE = 40;
    private static final int STACK_PATTERN_MATCH_SCORE = 30;
    
    // Configuration
    private int maxSuggestionsPerIssue = 5;
    private int minScoreThreshold = 30;
    
    public SolutionMatchingService(SolutionStore solutionStore) {
        this.solutionStore = solutionStore;
    }
    
    /**
     * Finds matching solutions for a given issue.
     * @param issue The issue to find solutions for
     * @return List of suggestion results, sorted by score descending
     */
    public List<SuggestionResult> findSuggestionsForIssue(LogIssue issue) {
        if (issue == null) {
            return Collections.emptyList();
        }
        
        // Get all active solutions
        List<IssueSolution> allSolutions = solutionStore.getAllActiveSolutions();
        
        // Score each solution
        List<SuggestionResult> results = new ArrayList<>();
        for (IssueSolution solution : allSolutions) {
            int score = calculateMatchScore(solution, issue);
            if (score >= minScoreThreshold) {
                results.add(new SuggestionResult(solution, score, describeMatch(solution, issue, score)));
            }
        }
        
        // Sort by score descending, then by upvotes
        results.sort((a, b) -> {
            int scoreCompare = Integer.compare(b.score, a.score);
            if (scoreCompare != 0) return scoreCompare;
            return Integer.compare(b.solution.getUpvotes(), a.solution.getUpvotes());
        });
        
        // Limit results
        if (results.size() > maxSuggestionsPerIssue) {
            results = results.subList(0, maxSuggestionsPerIssue);
        }
        
        // Record matches and increment usage counts
        for (SuggestionResult result : results) {
            SolutionMatch match = new SolutionMatch(result.solution.getId(), issue.getId(), result.score);
            solutionStore.recordMatch(match);
            result.matchId = match.getId();
            solutionStore.incrementUsageCount(result.solution.getId());
        }
        
        return results;
    }
    
    /**
     * Calculates the match score between a solution and an issue.
     */
    private int calculateMatchScore(IssueSolution solution, LogIssue issue) {
        int score = 0;
        
        String issueType = issue.getIssueType();
        String issueMessage = issue.getMessage();
        String stackTrace = issue.getFullStackTrace();
        String solutionPattern = solution.getIssuePattern();
        
        // 1. Issue Type Matching
        if (issueType != null && solutionPattern != null) {
            String normalizedIssueType = issueType.toLowerCase().trim();
            String normalizedPattern = solutionPattern.toLowerCase().trim();
            
            // Exact match
            if (normalizedIssueType.equals(normalizedPattern)) {
                score += EXACT_TYPE_MATCH_SCORE;
            }
            // Issue type contains pattern (e.g., "java.lang.NullPointerException" contains "NullPointerException")
            else if (normalizedIssueType.contains(normalizedPattern)) {
                score += CONTAINS_TYPE_MATCH_SCORE;
            }
            // Pattern contains issue type
            else if (normalizedPattern.contains(normalizedIssueType)) {
                score += CONTAINS_TYPE_MATCH_SCORE;
            }
            // Partial match (common words)
            else if (hasSignificantOverlap(normalizedIssueType, normalizedPattern)) {
                score += PARTIAL_TYPE_MATCH_SCORE;
            }
        }
        
        // 2. Message Pattern Matching
        String messagePattern = solution.getMessagePattern();
        if (messagePattern != null && !messagePattern.isEmpty() && issueMessage != null) {
            if (matchesPattern(issueMessage, messagePattern)) {
                score += MESSAGE_PATTERN_MATCH_SCORE;
            }
        }
        
        // 3. Stack Trace Pattern Matching
        String stackPattern = solution.getStackPattern();
        if (stackPattern != null && !stackPattern.isEmpty() && stackTrace != null) {
            if (matchesPattern(stackTrace, stackPattern)) {
                score += STACK_PATTERN_MATCH_SCORE;
            }
        }
        
        return score;
    }
    
    /**
     * Checks if a text matches a pattern (supports simple wildcards and regex).
     */
    private boolean matchesPattern(String text, String pattern) {
        if (text == null || pattern == null) {
            return false;
        }
        
        String normalizedText = text.toLowerCase();
        String normalizedPattern = pattern.toLowerCase();
        
        // Simple contains check first
        if (normalizedText.contains(normalizedPattern)) {
            return true;
        }
        
        // Try regex matching
        try {
            Pattern regex = Pattern.compile(normalizedPattern, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            return regex.matcher(text).find();
        } catch (PatternSyntaxException e) {
            // If invalid regex, try as simple wildcard pattern
            String wildcardRegex = normalizedPattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");
            try {
                Pattern regex = Pattern.compile(wildcardRegex, Pattern.CASE_INSENSITIVE);
                return regex.matcher(normalizedText).find();
            } catch (PatternSyntaxException e2) {
                return false;
            }
        }
    }
    
    /**
     * Checks if two strings have significant word overlap.
     */
    private boolean hasSignificantOverlap(String s1, String s2) {
        Set<String> words1 = extractSignificantWords(s1);
        Set<String> words2 = extractSignificantWords(s2);
        
        if (words1.isEmpty() || words2.isEmpty()) {
            return false;
        }
        
        // Count matching words
        long matchingWords = words1.stream()
            .filter(w -> words2.stream().anyMatch(w2 -> w2.contains(w) || w.contains(w2)))
            .count();
        
        // Require at least one significant word match
        return matchingWords >= 1;
    }
    
    /**
     * Extracts significant words (excluding common ones) from a string.
     */
    private Set<String> extractSignificantWords(String text) {
        Set<String> stopWords = Set.of("java", "lang", "util", "io", "net", "com", "org", "exception", "error");
        
        return Arrays.stream(text.split("[^a-zA-Z0-9]+"))
            .map(String::toLowerCase)
            .filter(w -> w.length() > 3)
            .filter(w -> !stopWords.contains(w))
            .collect(Collectors.toSet());
    }
    
    /**
     * Creates a human-readable description of why the solution matched.
     */
    private String describeMatch(IssueSolution solution, LogIssue issue, int score) {
        List<String> reasons = new ArrayList<>();
        
        String issueType = issue.getIssueType();
        String solutionPattern = solution.getIssuePattern();
        
        if (issueType != null && solutionPattern != null) {
            if (issueType.equalsIgnoreCase(solutionPattern)) {
                reasons.add("Exact issue type match");
            } else if (issueType.toLowerCase().contains(solutionPattern.toLowerCase()) ||
                       solutionPattern.toLowerCase().contains(issueType.toLowerCase())) {
                reasons.add("Issue type pattern match");
            }
        }
        
        if (solution.getMessagePattern() != null && issue.getMessage() != null &&
            matchesPattern(issue.getMessage(), solution.getMessagePattern())) {
            reasons.add("Message pattern match");
        }
        
        if (solution.getStackPattern() != null && issue.getFullStackTrace() != null &&
            matchesPattern(issue.getFullStackTrace(), solution.getStackPattern())) {
            reasons.add("Stack trace pattern match");
        }
        
        if (reasons.isEmpty()) {
            reasons.add("Partial pattern match");
        }
        
        return String.join(", ", reasons);
    }
    
    // Configuration setters
    
    public void setMaxSuggestionsPerIssue(int max) {
        this.maxSuggestionsPerIssue = max;
    }
    
    public void setMinScoreThreshold(int threshold) {
        this.minScoreThreshold = threshold;
    }
    
    /**
     * Container for a suggestion result.
     */
    public static class SuggestionResult {
        public final IssueSolution solution;
        public final int score;
        public final String matchReason;
        public String matchId; // Set after recording the match
        
        public SuggestionResult(IssueSolution solution, int score, String matchReason) {
            this.solution = solution;
            this.score = score;
            this.matchReason = matchReason;
        }
        
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("solutionId", solution.getId());
            map.put("issuePattern", solution.getIssuePattern());
            map.put("title", solution.getSolutionTitle());
            map.put("description", solution.getSolutionDescription());
            map.put("score", score);
            map.put("matchReason", matchReason);
            map.put("matchId", matchId);
            map.put("upvotes", solution.getUpvotes());
            map.put("downvotes", solution.getDownvotes());
            map.put("rating", solution.getRating());
            map.put("usageCount", solution.getUsageCount());
            map.put("createdAt", solution.getFormattedCreatedAt());
            map.put("createdBy", solution.getCreatedBy());
            return map;
        }
    }
}
