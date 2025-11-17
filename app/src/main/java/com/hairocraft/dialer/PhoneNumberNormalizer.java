package com.hairocraft.dialer;

import android.util.Log;

/**
 * Phone number normalization utility for better matching accuracy
 * Implements E.164 format normalization and international number handling
 *
 * PHASE 2.1: Phone Normalization
 */
public class PhoneNumberNormalizer {
    private static final String TAG = "PhoneNormalizer";

    // Common country codes for the region (can be configured)
    private static final String DEFAULT_COUNTRY_CODE = "60"; // Malaysia

    /**
     * Normalize a phone number to E.164 format for consistent matching
     *
     * Examples:
     * - "+60123456789" -> "60123456789"
     * - "0123456789" -> "60123456789" (Malaysia)
     * - "123456789" -> "60123456789" (Malaysia)
     * - "+1-555-123-4567" -> "15551234567"
     *
     * @param phoneNumber The raw phone number
     * @return Normalized phone number (digits only, with country code)
     */
    public static String normalize(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isEmpty()) {
            return "";
        }

        // Step 1: Strip all non-digit characters except leading +
        String cleaned = phoneNumber.trim();
        boolean hasPlus = cleaned.startsWith("+");
        cleaned = cleaned.replaceAll("[^0-9]", "");

        if (cleaned.isEmpty()) {
            return "";
        }

        // Step 2: Handle country codes
        if (hasPlus) {
            // Number already has country code (e.g., +60123456789)
            // Just return the digits (remove the +)
            return cleaned;
        }

        // Step 3: Handle local numbers without country code
        if (cleaned.startsWith("0")) {
            // Malaysian local format: 0123456789 -> 60123456789
            // Remove leading 0 and add country code
            return DEFAULT_COUNTRY_CODE + cleaned.substring(1);
        } else if (cleaned.startsWith(DEFAULT_COUNTRY_CODE)) {
            // Already has country code without +: 60123456789
            return cleaned;
        } else if (cleaned.length() >= 9 && cleaned.length() <= 11) {
            // Likely a local number without leading 0
            // Add country code
            return DEFAULT_COUNTRY_CODE + cleaned;
        }

        // Step 4: Return as-is if we can't determine format
        return cleaned;
    }

    /**
     * Normalize and get multiple variants for fuzzy matching
     * Returns possible number variants to check against
     *
     * @param phoneNumber The raw phone number
     * @return Array of normalized variants
     */
    public static String[] getNormalizedVariants(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isEmpty()) {
            return new String[]{};
        }

        String cleaned = phoneNumber.replaceAll("[^0-9]", "");

        if (cleaned.isEmpty()) {
            return new String[]{};
        }

        // Generate common variants
        java.util.Set<String> variants = new java.util.LinkedHashSet<>();

        // Variant 1: Fully normalized
        variants.add(normalize(phoneNumber));

        // Variant 2: Just digits
        variants.add(cleaned);

        // Variant 3: With leading 0 (Malaysian local format)
        if (!cleaned.startsWith("0") && cleaned.length() >= 9) {
            variants.add("0" + cleaned.replaceFirst("^" + DEFAULT_COUNTRY_CODE, ""));
        }

        // Variant 4: Without country code
        if (cleaned.startsWith(DEFAULT_COUNTRY_CODE)) {
            String withoutCountryCode = cleaned.substring(DEFAULT_COUNTRY_CODE.length());
            variants.add(withoutCountryCode);
            variants.add("0" + withoutCountryCode);
        }

        // Variant 5: Last 9 digits (for partial matches)
        if (cleaned.length() >= 9) {
            variants.add(cleaned.substring(cleaned.length() - 9));
        }

        // Variant 6: Last 10 digits
        if (cleaned.length() >= 10) {
            variants.add(cleaned.substring(cleaned.length() - 10));
        }

        return variants.toArray(new String[0]);
    }

    /**
     * Check if a filename contains any variant of the phone number
     *
     * @param filename The filename to check
     * @param phoneNumber The phone number to look for
     * @return true if any variant is found in the filename
     */
    public static boolean filenameContainsNumber(String filename, String phoneNumber) {
        if (filename == null || phoneNumber == null) {
            return false;
        }

        String lowerFilename = filename.toLowerCase();
        String[] variants = getNormalizedVariants(phoneNumber);

        for (String variant : variants) {
            if (!variant.isEmpty() && lowerFilename.contains(variant)) {
                Log.d(TAG, "Found phone variant '" + variant + "' in filename: " + filename);
                return true;
            }
        }

        return false;
    }

    /**
     * Get the most likely normalized form for comparison
     * This is the canonical form used for direct comparisons
     *
     * @param phoneNumber The raw phone number
     * @return Canonical normalized form
     */
    public static String getCanonicalForm(String phoneNumber) {
        return normalize(phoneNumber);
    }

    /**
     * Compare two phone numbers for equality (fuzzy match)
     * Returns true if the numbers likely refer to the same contact
     *
     * @param number1 First phone number
     * @param number2 Second phone number
     * @return true if numbers match
     */
    public static boolean areNumbersEqual(String number1, String number2) {
        if (number1 == null || number2 == null) {
            return false;
        }

        String canonical1 = getCanonicalForm(number1);
        String canonical2 = getCanonicalForm(number2);

        if (canonical1.equals(canonical2)) {
            return true;
        }

        // Fallback: Check if last 9 digits match (handles different country code formats)
        if (canonical1.length() >= 9 && canonical2.length() >= 9) {
            String suffix1 = canonical1.substring(canonical1.length() - 9);
            String suffix2 = canonical2.substring(canonical2.length() - 9);
            return suffix1.equals(suffix2);
        }

        return false;
    }
}
