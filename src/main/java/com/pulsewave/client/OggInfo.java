package com.pulsewave.client;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;

/**
 * Tiny Ogg Vorbis reader: format check + duration (last granule position / sample rate).
 * Never throws: anything unexpected just means "unknown" (0 / false).
 */
final class OggInfo {
    private OggInfo() {}

    /** 0x01 followed by "vorbis" = the Vorbis identification header. */
    private static final byte[] MARK = {1, 'v', 'o', 'r', 'b', 'i', 's'};

    private static byte[] head(RandomAccessFile f) throws IOException {
        byte[] h = new byte[(int) Math.min(f.length(), 1024)];
        f.readFully(h);
        return h;
    }

    private static int findMarker(byte[] h) {
        for (int i = 0; i + MARK.length <= h.length; i++) {
            boolean ok = true;
            for (int j = 0; j < MARK.length; j++) {
                if (h[i + j] != MARK[j]) { ok = false; break; }
            }
            if (ok) return i;
        }
        return -1;
    }

    static boolean isVorbis(Path p) {
        try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "r")) {
            byte[] h = head(f);
            return h.length >= 4 && h[0] == 'O' && h[1] == 'g' && h[2] == 'g' && h[3] == 'S' && findMarker(h) >= 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Duration in seconds, or 0 if it can't be determined. */
    static float seconds(Path p) {
        try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "r")) {
            byte[] h = head(f);
            int m = findMarker(h);
            if (m < 0 || m + 16 > h.length) return 0;

            // after the marker: version (4 bytes), channels (1 byte), sample rate (4 bytes, little endian)
            int o = m + 12;
            long rate = (h[o] & 0xFFL) | (h[o + 1] & 0xFFL) << 8 | (h[o + 2] & 0xFFL) << 16 | (h[o + 3] & 0xFFL) << 24;
            if (rate <= 0) return 0;

            long len = f.length();
            int n = (int) Math.min(len, 65536);
            byte[] tail = new byte[n];
            f.seek(len - n);
            f.readFully(tail);
            for (int i = n - 14; i >= 0; i--) {
                if (tail[i] == 'O' && tail[i + 1] == 'g' && tail[i + 2] == 'g' && tail[i + 3] == 'S') {
                    long g = 0;
                    for (int b = 7; b >= 0; b--) g = (g << 8) | (tail[i + 6 + b] & 0xFFL);
                    return g > 0 ? (float) g / rate : 0;
                }
            }
        } catch (Exception ignored) { }
        return 0;
    }
}
