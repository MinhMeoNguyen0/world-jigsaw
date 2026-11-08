package com.worldjigsaw.chat;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.lang.UCharacter.GraphemeClusterBreak;
import com.ibm.icu.lang.UCharacter.IndicSyllabicCategory;
import com.ibm.icu.lang.UProperty;
import com.ibm.icu.lang.UScript;

final class GraphemeCounter {
  private int previous = -1;
  private int regionalParity;
  private int emoji;
  private int conjunct;
  private int count;

  int add(int codePoint) {
    int current = UCharacter.getIntPropertyValue(codePoint, UProperty.GRAPHEME_CLUSTER_BREAK);
    boolean pictographic = UCharacter.hasBinaryProperty(codePoint, UProperty.EXTENDED_PICTOGRAPHIC);
    int script = UScript.getScript(codePoint);
    boolean linkingScript =
        script == UScript.GUJARATI
            || script == UScript.TELUGU
            || script == UScript.MALAYALAM
            || script == UScript.ORIYA
            || script == UScript.BENGALI
            || script == UScript.DEVANAGARI;
    int syllabic =
        linkingScript
            ? UCharacter.getIntPropertyValue(codePoint, UProperty.INDIC_SYLLABIC_CATEGORY)
            : -1;
    boolean consonant = syllabic == IndicSyllabicCategory.CONSONANT;
    boolean virama = syllabic == IndicSyllabicCategory.VIRAMA;
    boolean breaks = previous == -1 || boundary(current, pictographic, consonant);
    if (breaks) count++;

    regionalParity = current == GraphemeClusterBreak.REGIONAL_INDICATOR ? 1 - regionalParity : 0;
    if (pictographic) emoji = 1;
    else if (current == GraphemeClusterBreak.ZWJ) emoji = emoji == 1 ? 2 : 0;
    else if (current != GraphemeClusterBreak.EXTEND || emoji != 1) emoji = 0;
    if (consonant) conjunct = 1;
    else if (conjunct != 0 && virama) conjunct = 2;
    else if (current != GraphemeClusterBreak.ZWJ
        && !(current == GraphemeClusterBreak.EXTEND
            && UCharacter.getCombiningClass(codePoint) != 0)) conjunct = 0;
    previous = current;
    return count;
  }

  private boolean boundary(int current, boolean pictographic, boolean consonant) {
    if (previous == GraphemeClusterBreak.CR && current == GraphemeClusterBreak.LF) return false;
    if (control(previous) || control(current)) return true;
    if (previous == GraphemeClusterBreak.L
        && (current == GraphemeClusterBreak.L
            || current == GraphemeClusterBreak.V
            || current == GraphemeClusterBreak.LV
            || current == GraphemeClusterBreak.LVT)) return false;
    if ((previous == GraphemeClusterBreak.LV || previous == GraphemeClusterBreak.V)
        && (current == GraphemeClusterBreak.V || current == GraphemeClusterBreak.T)) return false;
    if ((previous == GraphemeClusterBreak.LVT || previous == GraphemeClusterBreak.T)
        && current == GraphemeClusterBreak.T) return false;
    if (current == GraphemeClusterBreak.EXTEND
        || current == GraphemeClusterBreak.ZWJ
        || current == GraphemeClusterBreak.SPACING_MARK
        || previous == GraphemeClusterBreak.PREPEND) return false;
    if (conjunct == 2 && consonant) return false;
    if (emoji == 2 && pictographic) return false;
    return previous != GraphemeClusterBreak.REGIONAL_INDICATOR
        || current != GraphemeClusterBreak.REGIONAL_INDICATOR
        || regionalParity == 0;
  }

  private static boolean control(int property) {
    return property == GraphemeClusterBreak.CONTROL
        || property == GraphemeClusterBreak.CR
        || property == GraphemeClusterBreak.LF;
  }
}
