package com.ast.re4decomp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Custom music (a modification, not part of CubeEvil4): the game's music streams (bgm/bio4str.hed, bgm/bio4bgm.sbb,
 * DSP ADPCM, stereo in blocks of 0x4000). A new song goes to the end of a copy of the .sbb and the track's entry in a
 * copy of the .hed points to it; the imported disc files are only read. Plain Java, no Android.
 */
final class Re4Music {
  private static final int BLOCK = 0x4000;
  private final File discHed, discSbb, hed, sbb;

  Re4Music(File discFiles, File musicDir) {
    discHed = new File(discFiles, "bgm/bio4str.hed");
    discSbb = new File(discFiles, "bgm/bio4bgm.sbb");
    hed = new File(musicDir, "bgm/bio4str.hed");
    sbb = new File(musicDir, "bgm/bio4bgm.sbb");
  }

  boolean discPresent() { return discHed.isFile() && discSbb.isFile(); }

  private byte[] table(boolean original) throws IOException {
    return Files.readAllBytes((!original && hed.isFile() ? hed : discHed).toPath());
  }

  private static int entry(byte[] d, int track) {
    ByteBuffer b = ByteBuffer.wrap(d);
    int base = b.getInt(0);
    return base + b.getInt(base + b.getInt(base + 4) + 4 * track);
  }

  int count() throws IOException {
    byte[] d = table(true);
    ByteBuffer b = ByteBuffer.wrap(d);
    return b.getInt(b.getInt(0));
  }

  int rate(int track) throws IOException {
    byte[] d = table(true);
    return ByteBuffer.wrap(d).getInt(entry(d, track) + 0x1C);
  }

  int seconds(int track) throws IOException {
    byte[] d = table(false);
    ByteBuffer b = ByteBuffer.wrap(d);
    int e = entry(d, track);
    return b.getInt(e + 0x14) / Math.max(1, b.getInt(e + 0x1C));
  }

  boolean replaced(int track) throws IOException {
    if (!hed.isFile()) return false;
    byte[] a = table(true), c = table(false);
    return ByteBuffer.wrap(a).getInt(entry(a, track) + 0x2C) != ByteBuffer.wrap(c).getInt(entry(c, track) + 0x2C);
  }

  private static short[] coefs(byte[] d, int e, int channel) {
    short[] r = new short[16];
    ByteBuffer.wrap(d, e + 0x30 + channel * 0x20, 32).asShortBuffer().get(r);
    return r;  // 8 x c1, then 8 x c2
  }

  /** The first `maxSeconds` of a track as interleaved stereo. */
  short[] decode(int track, boolean original, int maxSeconds) throws IOException {
    byte[] d = table(original);
    ByteBuffer b = ByteBuffer.wrap(d);
    int e = entry(d, track);
    int samples = Math.min(b.getInt(e + 0x14), b.getInt(e + 0x1C) * maxSeconds);
    long pos = b.getInt(e + 0x2C) & 0xFFFFFFFFL;
    short[] out = new short[samples * 2];
    byte[] block = new byte[BLOCK];
    try (RandomAccessFile f = new RandomAccessFile(!original && sbb.isFile() ? sbb : discSbb, "r")) {
      for (int c = 0; c < 2; c++) {
        short[] k = coefs(d, e, c);
        int h1 = 0, h2 = 0, n = 0;
        for (int blk = 0; n < samples; blk++) {
          long at = pos + (long) blk * 2 * BLOCK + (long) c * BLOCK;
          if (at >= f.length()) break;
          f.seek(at);
          int got = (int) Math.min(BLOCK, f.length() - at);
          f.readFully(block, 0, got);
          for (int i = 0; i + 8 <= got && n < samples; i += 8) {
            int head = block[i] & 255, scale = 1 << (head & 15), p = (head >> 4) & 7;
            for (int j = 0; j < 14 && n < samples; j++) {
              int byt = block[i + 1 + j / 2];
              int q = (j & 1) == 0 ? byt >> 4 : (byt << 28) >> 28;
              int s = (((q * scale) << 11) + 1024 + k[p] * h1 + k[8 + p] * h2) >> 11;
              s = Math.max(-32768, Math.min(32767, s));
              h2 = h1;
              h1 = s;
              out[n++ * 2 + c] = (short) s;
            }
          }
        }
      }
    }
    return out;
  }

  /** One channel of interleaved stereo to DSP ADPCM with the track's own 8 coefficient pairs. */
  static byte[] encode(short[] pcm, int channel, int samples, short[] k) {
    byte[] out = new byte[(samples + 13) / 14 * 8];
    int h1 = 0, h2 = 0, o = 0;
    int[] frame = new int[14], nib = new int[14];
    for (int start = 0; start < samples; start += 14) {
      for (int j = 0; j < 14; j++) {
        frame[j] = start + j < samples ? pcm[(start + j) * 2 + channel] : 0;
      }
      long best = Long.MAX_VALUE;
      int p = 0, peak = 0;
      for (int i = 0; i < 8; i++) {
        int a = h1, b = h2, max = 0;
        long total = 0;
        for (int s : frame) {
          int err = s - ((k[i] * a + k[8 + i] * b + 1024) >> 11);
          total += (long) err * err;
          max = Math.max(max, Math.abs(err));
          b = a;
          a = s;
        }
        if (total < best) {
          best = total;
          p = i;
          peak = max;
        }
      }
      int c1 = k[p], c2 = k[8 + p], scale = 0;
      while (scale < 12 && (7 << scale) < peak) scale++;
      int a, b;
      while (true) {
        a = h1;
        b = h2;
        boolean clipped = false;
        int step = 1 << scale;
        for (int j = 0; j < 14; j++) {
          long pred = (long) c1 * a + (long) c2 * b;
          long q = (((long) frame[j] << 11) - pred + ((long) step << 10)) >> (11 + scale);
          if (q > 7) {
            clipped |= q > 8;
            q = 7;
          } else if (q < -8) {
            clipped |= q < -9;
            q = -8;
          }
          long v = (((q * step) << 11) + 1024 + pred) >> 11;
          b = a;
          a = (int) Math.max(-32768, Math.min(32767, v));
          nib[j] = (int) q & 15;
        }
        if (!clipped || scale >= 12) break;
        scale++;
      }
      h1 = a;
      h2 = b;
      out[o++] = (byte) (p << 4 | scale);
      for (int j = 0; j < 14; j += 2) out[o++] = (byte) (nib[j] << 4 | nib[j + 1]);
    }
    return out;
  }

  /** Puts a song (interleaved stereo at rate(track)) on a track, in its full length. */
  void replace(int track, short[] pcm) throws IOException {
    int samples = pcm.length / 2;
    if (samples == 0) throw new IOException("La canción no tiene audio");
    byte[] d = table(false);
    int e = entry(d, track);
    byte[][] channel = new byte[2][];
    for (int c = 0; c < 2; c++) channel[c] = encode(pcm, c, samples, coefs(d, e, c));
    File folder = sbb.getParentFile();
    if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("No se pudo crear " + folder);
    if (!sbb.isFile()) {
      File tmp = new File(folder, "bio4bgm.tmp");
      Files.copy(discSbb.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
      if (!tmp.renameTo(sbb)) throw new IOException("No se pudo crear " + sbb);
    }
    long pos;
    try (RandomAccessFile f = new RandomAccessFile(sbb, "rw")) {
      pos = (f.length() + 2 * BLOCK - 1) / (2 * BLOCK) * (2 * BLOCK);
      f.setLength(pos);
      f.seek(pos);
      for (int at = 0; at < channel[0].length; at += BLOCK) {
        for (int c = 0; c < 2; c++) {
          byte[] block = new byte[BLOCK];
          System.arraycopy(channel[c], at, block, 0, Math.min(BLOCK, channel[c].length - at));
          f.write(block);
        }
      }
    }
    int rest = samples % 14, nibbles = samples / 14 * 16 + (rest != 0 ? rest + 2 : 0);
    ByteBuffer b = ByteBuffer.wrap(d);
    b.putInt(e + 0x14, samples).putInt(e + 0x18, nibbles);
    b.putInt(e + 0x28, nibbles - 1).putInt(e + 0x2C, (int) pos);
    save(d);
  }

  /** The track plays the disc's music again (its song stays unused in the .sbb until "restore all"). */
  void restore(int track) throws IOException {
    if (!hed.isFile()) return;
    byte[] original = table(true), d = table(false);
    int from = entry(original, track), to = entry(d, track);
    System.arraycopy(original, from + 0x14, d, to + 0x14, 8);
    System.arraycopy(original, from + 0x28, d, to + 0x28, 8);
    save(d);
  }

  void restoreAll() throws IOException {
    Files.deleteIfExists(hed.toPath());
    Files.deleteIfExists(sbb.toPath());
  }

  private void save(byte[] d) throws IOException {
    File tmp = new File(hed.getParentFile(), "bio4str.tmp");
    try (FileOutputStream o = new FileOutputStream(tmp)) {
      o.write(d);
    }
    if (!tmp.renameTo(hed)) throw new IOException("No se pudo guardar " + hed);
  }
}
