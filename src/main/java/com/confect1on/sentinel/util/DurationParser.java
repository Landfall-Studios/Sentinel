package com.confect1on.sentinel.util;

import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for parsing duration strings like "1h", "3d", "30m" into time periods.
 */
public class DurationParser {
    private static final Pattern DURATION_PATTERN = Pattern.compile("^(\\d+)([smhdw])$");
    
    /**
     * Parses a duration string and returns the number of seconds.
     * 
     * Supported formats:
     * - s: seconds (e.g., "30s")
     * - m: minutes (e.g., "15m")
     * - h: hours (e.g., "2h")
     * - d: days (e.g., "7d")
     * - w: weeks (e.g., "2w")
     * 
     * @param duration The duration string to parse
     * @return The duration in seconds, or -1 if invalid
     */
    public static long parseDurationToSeconds(String duration) {
        if (duration == null || duration.trim().isEmpty()) {
            return -1;
        }
        
        Matcher matcher = DURATION_PATTERN.matcher(duration.trim().toLowerCase());
        if (!matcher.matches()) {
            return -1;
        }
        
        long value = Long.parseLong(matcher.group(1));
        String unit = matcher.group(2);
        
        return switch (unit) {
            case "s" -> value;
            case "m" -> value * 60;
            case "h" -> value * 3600;
            case "d" -> value * 86400;
            case "w" -> value * 604800;
            default -> -1;
        };
    }
    
    /**
     * Parses a duration string and returns an Instant representing when it expires.
     * 
     * @param duration The duration string to parse
     * @return The expiration instant, or null if invalid or permanent
     */
    public static Instant parseDurationToExpiry(String duration) {
        long seconds = parseDurationToSeconds(duration);
        if (seconds <= 0) {
            return null;
        }
        return Instant.now().plusSeconds(seconds);
    }
    
    /**
     * Formats a duration in seconds to a human-readable string.
     * 
     * @param seconds The duration in seconds
     * @return A formatted string like "2d 3h 15m"
     */
    public static String formatDuration(long seconds) {
        if (seconds <= 0) {
            return "0s";
        }
        
        long weeks = seconds / 604800;
        seconds %= 604800;
        long days = seconds / 86400;
        seconds %= 86400;
        long hours = seconds / 3600;
        seconds %= 3600;
        long minutes = seconds / 60;
        seconds %= 60;
        
        StringBuilder sb = new StringBuilder();
        if (weeks > 0) sb.append(weeks).append("w ");
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        if (seconds > 0) sb.append(seconds).append("s");
        
        return sb.toString().trim();
    }
    
    /**
     * Validates a duration string format.
     * 
     * @param duration The duration string to validate
     * @return true if the format is valid
     */
    public static boolean isValidDuration(String duration) {
        return parseDurationToSeconds(duration) > 0;
    }
} 