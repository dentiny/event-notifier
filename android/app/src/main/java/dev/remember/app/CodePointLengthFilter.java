package dev.remember.app;

import android.text.InputFilter;
import android.text.Spanned;

/** Uses the same Unicode code-point limits as reminder validation. */
final class CodePointLengthFilter implements InputFilter {
    private final int max;
    CodePointLengthFilter(int max) { this.max = max; }
    @Override public CharSequence filter(CharSequence source, int start, int end, Spanned dest, int dstart, int dend) {
        int keep = max - (Character.codePointCount(dest, 0, dest.length()) - Character.codePointCount(dest, dstart, dend));
        if (Character.codePointCount(source, start, end) <= keep) return null;
        if (keep <= 0) return "";
        return source.subSequence(start, Character.offsetByCodePoints(source, start, keep));
    }
}
