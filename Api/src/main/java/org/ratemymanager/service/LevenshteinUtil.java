package org.ratemymanager.service;

class LevenshteinUtil {

    static int distance(String a, String b) {
        String s1 = a.toLowerCase().trim();
        String s2 = b.toLowerCase().trim();
        int[] dp = new int[s2.length() + 1];
        for (int j = 0; j <= s2.length(); j++) dp[j] = j;
        for (int i = 1; i <= s1.length(); i++) {
            int prev = dp[0];
            dp[0] = i;
            for (int j = 1; j <= s2.length(); j++) {
                int temp = dp[j];
                dp[j] = s1.charAt(i - 1) == s2.charAt(j - 1)
                    ? prev
                    : 1 + Math.min(prev, Math.min(dp[j], dp[j - 1]));
                prev = temp;
            }
        }
        return dp[s2.length()];
    }
}
