package com.rg.player;

import java.util.Comparator;

/** 自然排序：数字按数值比较，避免 0,1,10,2 这种字典序问题。 */
public class NaturalComparator implements Comparator<String> {

    public static final NaturalComparator INSTANCE = new NaturalComparator();

    @Override
    public int compare(String a, String b) {
        if (a == null) return b == null ? 0 : -1;
        if (b == null) return 1;
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = stripZeros(a.substring(si, i));
                String nb = stripZeros(b.substring(sj, j));
                if (na.length() != nb.length()) return na.length() - nb.length();
                int c = na.compareTo(nb);
                if (c != 0) return c;
            } else {
                char la = Character.toLowerCase(ca);
                char lb = Character.toLowerCase(cb);
                if (la != lb) return la - lb;
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }

    private static String stripZeros(String s) {
        int k = 0;
        while (k < s.length() - 1 && s.charAt(k) == '0') k++;
        return s.substring(k);
    }
}
