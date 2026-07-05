package com.yeonwoo.askwiki.search;

public final class SearchMath {

    private SearchMath() {
    }

    public static double cosineSimilarity(float[] left, float[] right) {
        int length = Math.min(left.length, right.length);
        if (length == 0) {
            return 0.0;
        }

        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;

        for (int i = 0; i < length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }

        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
