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
 * new weapon's name and its place in the case's order ("‹  Red9  ›   2/5") above the life gauge in the bottom right
 * corner of the game's picture, for 1.5 seconds. The name is the game's English item name, like every text in the
 * picture. CoreActivity asks the core every 100 ms while the switch is on (CoreBridge.quickWeaponState) and hands
 * the state over (update); a new count of switches shows the label.
 */
final class QuickWeaponLabel {
  static final long SHOW_MS = 1500, POLL_MS = 100;
  /** The label's colours: the game's gold on a dark plate (as the menu's highlights). */
  static final int TEXT = 0xFFEBE1B4, PLACE = 0xFFAAAAA0, PLATE = 0xC80A0E0C, EDGE = 0xFFC8BE8C;

  final TextView view;
  private int seen = -1;

  QuickWeaponLabel(Context c) {
    view = new TextView(c);
    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
    view.setTypeface(Typeface.DEFAULT_BOLD);
    view.setTextColor(TEXT);
    view.setGravity(Gravity.CENTER);
    view.setTextDirection(View.TEXT_DIRECTION_LTR);  // a weapon's name, left to right in every language
    float d = c.getResources().getDisplayMetrics().density;
    GradientDrawable plate = new GradientDrawable();
    plate.setColor(PLATE);
    plate.setCornerRadius(8 * d);
    plate.setStroke(Math.max(1, Math.round(1.5f * d)), EDGE);
    view.setBackground(plate);
    view.setPadding(Math.round(12 * d), Math.round(4 * d), Math.round(12 * d), Math.round(5 * d));
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
   * The core's state: `serial` the switches done; a new one shows `name` (its place, the case's count) above the life
   * gauge: the right edge 3 % inside the picture's right side, the bottom at 66 % of its height (the gauge fills the
   * corner's lowest third), the picture at (`x`, `y`) with `w` x `h` view pixels. Returns whether it showed.
   */
  boolean update(int serial, String name, int place, int count, float x, float y, float w, float h) {
    boolean fresh = seen >= 0 && serial != seen && name != null && !name.isEmpty();
    seen = serial;
    if (!fresh) return false;
    show(name, place, count, x, y, w, h);
    return true;
  }

  /** Shows the label now (also the tests' pictures). */
  void show(String name, int place, int count, float x, float y, float w, float h) {
    view.setText(text(name, place, count));
    view.setAlpha(1f);
    view.setVisibility(View.VISIBLE);
    view.animate().cancel();
    view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
    float right = x + w * 0.97f, bottom = y + h * 0.66f;
    view.setTranslationX(right - view.getMeasuredWidth());
    view.setTranslationY(bottom - view.getMeasuredHeight());
    view.removeCallbacks(hide);
    view.postDelayed(hide, SHOW_MS);
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
