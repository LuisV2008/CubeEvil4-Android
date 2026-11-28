package com.ast.re4decomp;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

/**
 * "Quick weapon switch" (1.1.7, wish of the project owner, 28.11.2026; runtime/quick_weapon.cpp): after a switch the
 * new weapon's name and its place in the case's order ("‹  Red9  ›   2/5") to the left of the life gauge in the bottom
 * right corner of the game's picture, its bottom on the gauge's lowest line, for 1.5 seconds; in the colour of the
 * player's name on the gauge ("Leon"), as large as the gauge's own words are with the picture's height. The name is the
 * game's English item name, like every text in the picture. CoreActivity asks the core every 100 ms while the switch
 * is on (CoreBridge.quickWeaponState) and hands the state over (update); a new count of switches shows the label.
 */
final class QuickWeaponLabel {
  static final long SHOW_MS = 1500, POLL_MS = 100;
  /**
   * The colours: NAME the player's name on the gauge as the game draws it (the brightest pixels of "Leon" in the PC's
   * pictures of r100 and r101 with the GX renderer: 150, 150, 136), the place a little darker; a dark plate as the
   * gauge's under the name, a soft dark edge around the letters.
   */
  static final int NAME = 0xFF969688, PLACE = 0xFF7A7A6E, PLATE = 0x99101010, SHADOW = 0xE6000000;
  /** The text's size and the gap to the gauge, of the picture's height (the gauge's own words: "Leon" ~3 %). */
  static final float SIZE = 0.042f, GAP = 0.015f;

  final TextView view;
  private int seen = -1;
  // (the touch controls shown: TouchControls.shownControls) what the label keeps clear of, window pixels
  private java.util.List<android.graphics.RectF> avoid = java.util.Collections.emptyList();
  private final GradientDrawable plate = new GradientDrawable();

  QuickWeaponLabel(Context c) {
    view = new TextView(c);
    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
    // the game's words on the gauge: bold, slanted, with serifs
    view.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC));
    view.setTextColor(NAME);
    view.setGravity(Gravity.CENTER);
    view.setIncludeFontPadding(false);
    view.setTextDirection(View.TEXT_DIRECTION_LTR);  // a weapon's name, left to right in every language
    plate.setColor(PLATE);
    view.setBackground(plate);
    view.setVisibility(View.GONE);
    hide = () -> view.animate().alpha(0f).setDuration(250).withEndAction(() -> {
      view.setVisibility(View.GONE);
      view.setAlpha(1f);
    });
  }

  /** The label's text: "‹  name  ›   place/count" (the place only with a count). */
  static CharSequence text(String name, int place, int count) {
    String s = "‹  " + name + "  ›";
    if (count <= 0 || place <= 0) return s;
    android.text.SpannableString t = new android.text.SpannableString(s + "   " + place + "/" + count);
    t.setSpan(new android.text.style.ForegroundColorSpan(PLACE), s.length(), t.length(), 0);
    t.setSpan(new android.text.style.RelativeSizeSpan(0.75f), s.length(), t.length(), 0);
    return t;
  }

  /**
   * The core's state: `serial` the switches done; a new one shows `name` (its place, the case's count) to the left of
   * the life gauge, whose left end and lowest line are at `gaugeLeft`, `gaugeBottom` (view pixels; below 0 when the
   * core does not know them: then above the gauge's corner), the picture at (`x`, `y`) with `w` x `h` view pixels.
   * Returns whether it showed.
   */
  boolean update(int serial, String name, int place, int count, float x, float y, float w, float h, float gaugeLeft,
      float gaugeBottom) {
    boolean fresh = seen >= 0 && serial != seen && name != null && !name.isEmpty();
    seen = serial;
    if (!fresh) return false;
    show(name, place, count, x, y, w, h, gaugeLeft, gaugeBottom);
    return true;
  }

  /**
   * The touch controls shown now (window pixels; null or empty: none): the label moves left past one it would cover,
   * on the same line.
   */
  void avoid(java.util.List<android.graphics.RectF> controls) {
    avoid = controls == null ? java.util.Collections.emptyList() : controls;
  }

  /** Shows the label now (also the tests' pictures). */
  void show(String name, int place, int count, float x, float y, float w, float h, float gaugeLeft, float gaugeBottom) {
    final float size = Math.max(10f, h * SIZE), gap = h * GAP;
    view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
    view.setShadowLayer(size * 0.12f, size * 0.04f, size * 0.05f, SHADOW);
    plate.setCornerRadius(size * 0.3f);
    view.setPadding(Math.round(size * 0.45f), Math.round(size * 0.12f), Math.round(size * 0.45f),
        Math.round(size * 0.16f));
    view.setText(text(name, place, count));
    view.setAlpha(1f);
    view.setVisibility(View.VISIBLE);
    view.animate().cancel();
    view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
    final boolean known = gaugeLeft >= 0 && gaugeBottom >= 0;
    // without the core's gauge: the corner's place of the 16:9 formats (the gauge's left end at 77 % of the width
    // there, its lowest line at 96 % of the height)
    final float right = (known ? gaugeLeft : x + w - h * 0.43f) - gap, bottom = known ? gaugeBottom : y + h * 0.96f;
    final float lw = view.getMeasuredWidth(), lh = view.getMeasuredHeight();
    view.setTranslationX(Math.max(x, clearOf(right, bottom, lw, lh, gap) - lw));
    view.setTranslationY(bottom - lh);
    view.removeCallbacks(hide);
    view.postDelayed(hide, SHOW_MS);
  }

  /** The label's right edge at or left of `right` where its box covers none of the controls to avoid. */
  private float clearOf(float right, float bottom, float lw, float lh, float gap) {
    for (int round = 0; round < 8; round++) {
      boolean moved = false;
      for (android.graphics.RectF c : avoid) {
        if (c.left < right && c.right > right - lw && c.top < bottom && c.bottom > bottom - lh) {
          right = c.left - gap;
          moved = true;
        }
      }
      if (!moved) break;
    }
    return right;
  }

  private final Runnable hide;

  /** Off: nothing shown, the next state starts the count anew. */
  void reset() {
    seen = -1;
    view.removeCallbacks(hide);
    view.animate().cancel();
    view.setVisibility(View.GONE);
  }
}
