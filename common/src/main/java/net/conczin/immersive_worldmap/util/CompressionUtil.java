package net.conczin.immersive_worldmap.util;

import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;

public class CompressionUtil {
    private static final LZ4Factory factory = LZ4Factory.fastestInstance();

    public static byte[] compress(byte[] data) {
        byte[] rle = rleEncode(data);
        LZ4Compressor c = factory.fastCompressor();
        int max = c.maxCompressedLength(rle.length);
        byte[] out = new byte[4 + max]; // prefix original RLE length
        writeInt(out, rle.length);
        int n = c.compress(rle, 0, rle.length, out, 4, max);
        return java.util.Arrays.copyOf(out, 4 + n);
    }

    public static byte[] decompress(byte[] compressed) {
        int rleLen = readInt(compressed);
        byte[] rle = new byte[rleLen];
        LZ4FastDecompressor d = factory.fastDecompressor();
        d.decompress(compressed, 4, rle, 0, rleLen);
        return rleDecode(rle);
    }

    // --- RLE (byte run-length) ---
    private static byte[] rleEncode(byte[] in) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(in.length);
        for (int i = 0; i < in.length; ) {
            byte v = in[i];
            int run = 1;
            while (i + run < in.length && in[i + run] == v && run < 255) run++;
            out.write(run);   // 1..255
            out.write(v);
            i += run;
        }
        return out.toByteArray();
    }

    private static byte[] rleDecode(byte[] in) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < in.length; i += 2) {
            int run = in[i] & 0xFF;
            byte v = in[i + 1];
            for (int k = 0; k < run; k++) out.write(v);
        }
        return out.toByteArray();
    }

    // --- helpers ---
    private static void writeInt(byte[] b, int v) {
        b[0] = (byte) (v >>> 24);
        b[1] = (byte) (v >>> 16);
        b[2] = (byte) (v >>> 8);
        b[3] = (byte) (v);
    }

    private static int readInt(byte[] b) {
        return ((b[0] & 0xFF) << 24)
               | ((b[1] & 0xFF) << 16)
               | ((b[2] & 0xFF) << 8)
               | (b[3] & 0xFF);
    }
}
