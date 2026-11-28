package com.ast.re4decomp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;
import android.widget.FrameLayout;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * "Quick weapon switch" (1.1.7): the label above the life gauge. The text and its place in the game's picture (always);
 * with RE4_LABEL_SHOT_BASE (a picture of the game, 4:3 with the letterbox bars of the PC's GX render) and RE4_MENU_SHOTS
 * the label drawn over it on a 1920 x 1080 screen with the picture in 16:9 as the app shows it, for the project
 * owner's pictures (quick-weapon-label.png).
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
      // the first state only counts; a new switch shows it
      assertTrue(!label.update(3, "TMP", 2, 4, 0, 0, 1920, 1080));
      assertTrue(label.update(4, "Rifle", 3, 4, 0, 0, 1920, 1080));
      assertEquals(View.VISIBLE, label.view.getVisibility());
      // its right edge 3 % inside the picture, its bottom at 66 % of its height
      assertEquals(1920 * 0.97f, label.view.getTranslationX() + label.view.getMeasuredWidth(), 1f);
      assertEquals(1080 * 0.66f, label.view.getTranslationY() + label.view.getMeasuredHeight(), 1f);
      assertTrue(!label.update(4, "Rifle", 3, 4, 0, 0, 1920, 1080));
      label.reset();
      assertEquals(View.GONE, label.view.getVisibility());
    }
  }

  @Test public void overTheGame() throws Exception {
    String folder = System.getenv("RE4_MENU_SHOTS"), base = System.getenv("RE4_LABEL_SHOT_BASE");
    Assume.assumeTrue(folder != null && !folder.isEmpty() && base != null && new File(base).isFile());
    Bitmap game = BitmapFactory.decodeFile(base);
    // the 16:9 part between the bars (rows 56..392 of 448)
    int top = game.getHeight() * 56 / 448, rows = game.getHeight() * 336 / 448;
    final int w = 1920, h = 1080;
    float scale = (float) h / rows;
    int pw = Math.round(game.getWidth() * scale), px = (w - pw) / 2;
    try (var controller = Robolectric.buildActivity(Activity.class).create()) {
      Activity activity = controller.get();
      FrameLayout root = new FrameLayout(activity);
      QuickWeaponLabel label = new QuickWeaponLabel(activity);
      root.addView(label.view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
          FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP | android.view.Gravity.LEFT));
      label.show("Red9", 2, 5, px, 0, pw, h);
      root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
      root.layout(0, 0, w, h);
      Bitmap picture = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
      Canvas canvas = new Canvas(picture);
      canvas.drawColor(0xff000000);
      canvas.drawBitmap(game, new Rect(0, top, game.getWidth(), top + rows), new Rect(px, 0, px + pw, h), null);
      root.draw(canvas);
      new File(folder).mkdirs();
      try (FileOutputStream out = new FileOutputStream(new File(folder, "quick-weapon-label.png"))) {
        picture.compress(Bitmap.CompressFormat.PNG, 100, out);
      }
    }
  }
}
