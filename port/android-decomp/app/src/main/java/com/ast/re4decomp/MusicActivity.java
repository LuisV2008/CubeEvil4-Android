package com.ast.re4decomp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** "Mi música" (a modification, not part of CubeEvil4): replace the game's music with the player's MP3 / WAV. */
public final class MusicActivity extends Activity {
  private static final int PICK = 71;
  private static final int MAX_MINUTES = 8;
  private Re4Music music;
  private ArrayAdapter<String> list;
  private TextView status;
  private ListView view;
  private AudioTrack player;
  private int chosen = -1;
  private boolean busy;

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    if (state != null) chosen = state.getInt("track", -1);
    File base = AppStorage.base(this);
    music = new Re4Music(new File(base, "game/disc1/files"), new File(base, "music"));
    LinearLayout body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    int p = Math.round(16 * getResources().getDisplayMetrics().density);
    body.setPadding(p, p, p, p);
    TextView about = new TextView(this);
    about.setText("Mi música: reemplaza la música del juego con tus canciones MP3 o WAV. Toca una pista para "
        + "escucharla o cambiarla. La canción suena completa y tus discos no se modifican. "
        + "Después cierra la app por completo y ábrela de nuevo.");
    body.addView(about);
    status = new TextView(this);
    status.setPadding(0, p / 2, 0, p / 2);
    body.addView(status);
    Button stop = new Button(this);
    stop.setText("Detener sonido");
    stop.setOnClickListener(v -> stopSound());
    body.addView(stop);
    Button all = new Button(this);
    all.setText("Restaurar toda la música original");
    all.setOnClickListener(v -> {
      if (busy) return;
      new AlertDialog.Builder(this).setMessage("¿Borrar todas tus canciones?")
          .setPositiveButton("Borrar", (d, w) -> work("Restaurando...", () -> music.restoreAll()))
          .setNegativeButton("Cancelar", null).show();
    });
    body.addView(all);
    view = new ListView(this);
    list = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
    view.setAdapter(list);
    view.setOnItemClickListener((a, v, position, id) -> menu(position));
    body.addView(view, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    setContentView(body);
    refresh();
  }

  @Override protected void onSaveInstanceState(Bundle state) {
    state.putInt("track", chosen);
    super.onSaveInstanceState(state);
  }

  @Override protected void onStop() {
    stopSound();
    super.onStop();
  }

  private static String name(int track) {
    switch (track) {
      case 7: return "Combate en la granja";
      case 58: return "Mercenarios: Wesker";
      case 60: return "Mercenarios: Leon";
      case 61: return "Mercenarios: Ada";
      case 62: return "Mercenarios: Krauser";
      default: return "";
    }
  }

  private void refresh() {
    if (!music.discPresent()) {
      status.setText("Primero importa el disco 1 en la pantalla de inicio.");
      return;
    }
    try {
      List<String> rows = new ArrayList<>();
      for (int i = 0; i < music.count(); i++) {
        rows.add("Pista " + i + (name(i).isEmpty() ? "" : " - " + name(i)) + "  (" + music.seconds(i) + " s)"
            + (music.replaced(i) ? "  ✔ tu canción" : ""));
      }
      list.clear();
      list.addAll(rows);
    } catch (IOException | RuntimeException e) {
      status.setText("No se pudo leer la música: " + e.getMessage());
    }
  }

  private void menu(int track) {
    if (busy) return;
    chosen = track;
    String[] options = {"Escuchar", "Escuchar la original", "Elegir canción (MP3 / WAV)", "Volver a la original"};
    new AlertDialog.Builder(this).setTitle("Pista " + track + " " + name(track)).setItems(options, (d, which) -> {
      if (which == 0) play(track, false);
      else if (which == 1) play(track, true);
      else if (which == 2) pick();
      else work("Restaurando...", () -> music.restore(track));
    }).show();
  }

  private void pick() {
    stopSound();
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*");
    try {
      startActivityForResult(intent, PICK);
    } catch (android.content.ActivityNotFoundException e) {
      status.setText("Este dispositivo no tiene selector de archivos.");
    }
  }

  @Override protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request != PICK || result != RESULT_OK || data == null || data.getData() == null || chosen < 0) return;
    Uri song = data.getData();
    int track = chosen;
    work("Convirtiendo, no cierres la app...", () -> {
      music.replace(track, readSong(song, music.rate(track)));
      getSharedPreferences(CoreActivity.GRAPHICS_PREFS, MODE_PRIVATE).edit().putBoolean("customMusic", true).apply();
    });
  }

  private interface Job { void run() throws Exception; }

  private void work(String message, Job job) {
    stopSound();
    busy = true;
    status.setText(message);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    new Thread(() -> {
      String error = null;
      try {
        job.run();
      } catch (OutOfMemoryError e) {
        error = "La canción es demasiado grande para este celular.";
      } catch (Exception e) {
        error = e.getMessage() == null ? e.toString() : e.getMessage();
      }
      String shown = error;
      runOnUiThread(() -> {
        busy = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        status.setText(shown == null ? "Listo. Cierra la app por completo y ábrela de nuevo." : "Error: " + shown);
        refresh();
      });
    }).start();
  }

  private void play(int track, boolean original) {
    stopSound();
    try {
      short[] pcm = music.decode(track, original, 30);
      player = new AudioTrack(AudioManager.STREAM_MUSIC, music.rate(track), AudioFormat.CHANNEL_OUT_STEREO,
          AudioFormat.ENCODING_PCM_16BIT, pcm.length * 2, AudioTrack.MODE_STATIC);
      player.write(pcm, 0, pcm.length);
      player.play();
    } catch (IOException | RuntimeException e) {
      status.setText("No se pudo reproducir: " + e.getMessage());
    }
  }

  private void stopSound() {
    if (player != null) {
      player.release();
      player = null;
    }
  }

  /** Growing array of 16-bit samples. */
  private static final class Samples {
    short[] data = new short[1 << 20];
    int size;
    void add(short s) {
      if (size == data.length) data = Arrays.copyOf(data, size + size / 2);
      data[size++] = s;
    }
  }

  /** Decodes the picked file with Android's codecs to stereo at `rate`. */
  private short[] readSong(Uri uri, int rate) throws Exception {
    MediaExtractor in = new MediaExtractor();
    MediaCodec codec = null;
    try {
      in.setDataSource(this, uri, null);
      MediaFormat format = null;
      for (int i = 0; i < in.getTrackCount() && format == null; i++) {
        MediaFormat f = in.getTrackFormat(i);
        String mime = f.getString(MediaFormat.KEY_MIME);
        if (mime != null && mime.startsWith("audio/")) {
          format = f;
          in.selectTrack(i);
        }
      }
      if (format == null) throw new IOException("El archivo no tiene audio");
      int sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
      int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
      int encoding = format.containsKey("pcm-encoding") ? format.getInteger("pcm-encoding")
          : AudioFormat.ENCODING_PCM_16BIT;
      Samples out = new Samples();
      String mime = format.getString(MediaFormat.KEY_MIME);
      if ("audio/raw".equals(mime)) {  // WAV: already samples
        ByteBuffer buffer = ByteBuffer.allocateDirect(1 << 20);
        int n;
        while ((n = in.readSampleData(buffer, 0)) >= 0) {
          buffer.position(0).limit(n);
          append(out, buffer, channels, encoding, sourceRate);
          buffer.clear();
          in.advance();
        }
      } else {
        codec = MediaCodec.createDecoderByType(mime);
        codec.configure(format, null, null, 0);
        codec.start();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean fed = false, done = false;
        int idle = 0;
        while (!done) {
          if (!fed) {
            int i = codec.dequeueInputBuffer(10000);
            if (i >= 0) {
              int n = in.readSampleData(codec.getInputBuffer(i), 0);
              if (n < 0) {
                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                fed = true;
              } else {
                codec.queueInputBuffer(i, 0, n, in.getSampleTime(), 0);
                in.advance();
              }
            }
          }
          int o = codec.dequeueOutputBuffer(info, 10000);
          if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            MediaFormat f = codec.getOutputFormat();
            sourceRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            if (f.containsKey("pcm-encoding")) encoding = f.getInteger("pcm-encoding");
          } else if (o >= 0) {
            ByteBuffer buffer = codec.getOutputBuffer(o);
            buffer.position(info.offset).limit(info.offset + info.size);
            append(out, buffer, channels, encoding, sourceRate);
            codec.releaseOutputBuffer(o, false);
            done = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            idle = 0;
          } else if (fed && ++idle > 500) {
            throw new IOException("El decodificador no responde");
          }
        }
      }
      return resample(out, sourceRate, rate);
    } finally {
      if (codec != null) codec.release();
      in.release();
    }
  }

  /** Adds decoded bytes as stereo (mono doubled, more channels: the first two). */
  private static void append(Samples out, ByteBuffer buffer, int channels, int encoding, int rate) throws IOException {
    if (encoding != AudioFormat.ENCODING_PCM_16BIT && encoding != AudioFormat.ENCODING_PCM_FLOAT) {
      throw new IOException("Formato WAV no admitido: usa 16 bits o MP3");
    }
    ByteBuffer b = buffer.slice().order(ByteOrder.nativeOrder());
    boolean real = encoding == AudioFormat.ENCODING_PCM_FLOAT;
    int bytes = real ? 4 : 2;
    int frames = b.remaining() / (bytes * channels);
    for (int i = 0; i < frames; i++) {
      for (int c = 0; c < 2; c++) {
        int at = (i * channels + Math.min(c, channels - 1)) * bytes;
        out.add(real ? (short) Math.max(-32768, Math.min(32767, Math.round(b.getFloat(at) * 32767))) : b.getShort(at));
      }
    }
    if (out.size / 2 > (long) rate * 60 * MAX_MINUTES) {
      throw new IOException("La canción dura más de " + MAX_MINUTES + " minutos");
    }
  }

  private static short[] resample(Samples in, int from, int to) {
    int frames = in.size / 2;
    if (from == to) return Arrays.copyOf(in.data, frames * 2);
    int n = (int) ((long) frames * to / from);
    short[] out = new short[n * 2];
    for (int i = 0; i < n; i++) {
      double x = (double) i * from / to;
      int a = (int) x, b = Math.min(a + 1, frames - 1);
      double t = x - a;
      for (int c = 0; c < 2; c++) {
        out[i * 2 + c] = (short) Math.round(in.data[a * 2 + c] * (1 - t) + in.data[b * 2 + c] * t);
      }
    }
    return out;
  }
}
