package com.ast.re4decomp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.view.View;
import android.widget.FrameLayout;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * "Quick weapon switch" (1.1.7): the label to the left of the life gauge, on its lowest line. The text and its place
 * (always); with RE4_LABEL_SHOTS, pictures for the project owner, ";" between them, each "base|x,y|name,place,count|
 * lang|touch|out": a picture of the game at the screen's size as the app shows it (gx_chunk_render with
 * RE4_GX_SCREEN), the gauge's left end and lowest line there (from the core's log), the label's text, the language
 * (de, en), 1 for the touch controls over it (the default layout) and the picture's file.
 */
@RunWith(org.robolectric.RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 33, application = Application.class, qualifiers = "w960dp-h540dp-land-xhdpi")
public class QuickWeaponLabelShotTest {
  @Test public void textAndPlace() {
    assertEquals("‹  Red9  ›   2/5", QuickWeaponLabel.text("Red9", 2, 5).toString());
    assertEquals("‹  Handgun  ›", QuickWeaponLabel.text("Handgun", 0, 0).toString());
    try (var controller = Robolectric.buildActivity(Activity.class).create()) {
      QuickWeaponLabel label = new QuickWeaponLabel(controller.get());
      new FrameLayout(controller.get()).addView(label.view, new FrameLayout.LayoutParams(
          FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
      // the first state only counts; a new switch shows it
      assertTrue(!label.update(3, "TMP", 2, 4, 0, 0, 1920, 1080, 1457, 1037));
      assertTrue(label.update(4, "Rifle", 3, 4, 0, 0, 1920, 1080, 1457, 1037));
      assertEquals(View.VISIBLE, label.view.getVisibility());
      // its right edge a gap (1.5 % of the picture's height) left of the gauge, its bottom on the gauge's lowest line
      assertEquals(1457 - 1080 * QuickWeaponLabel.GAP, label.view.getTranslationX() + label.view.getMeasuredWidth(),
          1f);
      assertEquals(1037f, label.view.getTranslationY() + label.view.getMeasuredHeight(), 1f);
      // as large as the picture's height asks (the gauge's own words grow with it)
      assertEquals(1080 * QuickWeaponLabel.SIZE, label.view.getTextSize(), 0.5f);
      assertEquals(QuickWeaponLabel.NAME, label.view.getCurrentTextColor());
      assertTrue(!label.update(4, "Rifle", 3, 4, 0, 0, 1920, 1080, 1457, 1037));
      // a core that does not know the gauge: the 16:9 formats' corner
      assertTrue(label.update(5, "Riot Gun", 1, 4, 0, 0, 1920, 1080, -1, -1));
      assertEquals(1080 * 0.96f, label.view.getTranslationY() + label.view.getMeasuredHeight(), 1f);
      assertTrue(label.view.getTranslationX() + label.view.getMeasuredWidth() < 1920 - 1080 * 0.43f);
      // a touch button in its way (B of the default layout at 16:9): the label goes left of it, on the same line
      label.avoid(java.util.Collections.singletonList(new android.graphics.RectF(1400, 870, 1518, 988)));
      assertTrue(label.update(6, "Striker", 2, 4, 0, 0, 1920, 1080, 1457, 1037));
      assertTrue(label.view.getTranslationX() + label.view.getMeasuredWidth() <= 1400f);
      assertEquals(1037f, label.view.getTranslationY() + label.view.getMeasuredHeight(), 1f);
      label.reset();
      assertEquals(View.GONE, label.view.getVisibility());
    }
  }

  @Test public void overTheGame() throws Exception {
    String list = System.getenv("RE4_LABEL_SHOTS");
    Assume.assumeTrue(list != null && !list.isEmpty());
    for (String one : list.split(";")) {
      String[] f = one.split("\\|");  // base | x,y | name,place,count | lang | touch | out
      shot(f[0], f[1], f[2], f[3], "1".equals(f[4]), f[5]);
    }
  }

  private static void shot(String base, String gauge, String what, String lang, boolean withTouch, String out)
      throws Exception {
    RuntimeEnvironment.setQualifiers((lang.isEmpty() ? "" : lang + "-") + "w960dp-h540dp-land-xhdpi");
    Bitmap game = BitmapFactory.decodeFile(base);
    final int w = game.getWidth(), h = game.getHeight();
    String[] g = gauge.split(","), n = what.split(",");
    try (var controller = Robolectric.buildActivity(Activity.class).create()) {
      Activity activity = controller.get();
      FrameLayout root = new FrameLayout(activity);
      TouchControls touch = null;
      if (withTouch) {
        touch = new TouchControls(activity, new CoreInput(), TouchShowRule.FORCE,
            new TouchControls.Host() {
              @Override public void select(boolean down, boolean finger) {}
              @Override public void editing(boolean on) {}
              @Override public void send() {}
              @Override public void r3(boolean down) {}
              @Override public void zoom(int direction, boolean down) {}
            });
        root.addView(touch.view(), new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));
        touch.update();
      }
      QuickWeaponLabel label = new QuickWeaponLabel(activity);
      root.addView(label.view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
          FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP | android.view.Gravity.LEFT));
      for (int pass = 0; pass < 2; pass++) {  // twice: the touch controls lay out their default layout on the first
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, w, h);
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
      }
      // as CoreActivity: clear of the touch controls shown
      label.avoid(touch != null ? touch.shownControls() : null);
      label.show(n[0], Integer.parseInt(n[1]), Integer.parseInt(n[2]), 0, 0, w, h, Float.parseFloat(g[0]),
          Float.parseFloat(g[1]));
      if (touch != null) {
        android.graphics.RectF box = new android.graphics.RectF(label.view.getTranslationX(),
            label.view.getTranslationY(), label.view.getTranslationX() + label.view.getMeasuredWidth(),
            label.view.getTranslationY() + label.view.getMeasuredHeight());
        for (android.graphics.RectF c : touch.shownControls()) {
          assertTrue(c + " / " + box, !android.graphics.RectF.intersects(c, box));
        }
      }
      root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
      root.layout(0, 0, w, h);
      Bitmap picture = game.copy(Bitmap.Config.ARGB_8888, true);
      root.draw(new Canvas(picture));
      File file = new File(out);
      file.getParentFile().mkdirs();
      try (FileOutputStream o = new FileOutputStream(file)) {
        picture.compress(Bitmap.CompressFormat.PNG, 100, o);
      }
    }
  }
}
