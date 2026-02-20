package net.conczin.immersive_worldmap.util;

import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;

import java.util.Arrays;

public final class CompressionUtil {
    private static final LZ4Factory LZ4 = LZ4Factory.fastestInstance();
    private static final LZ4Compressor C = LZ4.fastCompressor();
    private static final LZ4FastDecompressor D = LZ4.fastDecompressor();

    private static final ThreadLocal<byte[]> RLE_BUF = ThreadLocal.withInitial(() -> new byte[0]);
    private static final ThreadLocal<byte[]> COMP_BUF = ThreadLocal.withInitial(() -> new byte[0]);

    private static byte[] ensure(ThreadLocal<byte[]> tl, int need) {
        byte[] b = tl.get();
        if (b.length < need) {
            b = new byte[Math.max(need, b.length + (b.length >>> 1) + 64)];
            tl.set(b);
        }
        return b;
    }

    private static int rleEncodeInto(byte[] src, int srcLen, byte[] dst) {
        int o = 0;
        for (int i = 0; i < srcLen; ) {
            byte v = src[i];
            int run = 1;
            while (i + run < srcLen && src[i + run] == v && run < 255) run++;
            dst[o++] = (byte) run;
            dst[o++] = v;
            i += run;
        }
        return o;
    }

    public static byte[] compress(byte[] data) {
        byte[] rle = ensure(RLE_BUF, data.length * 2);
        int rleLen = rleEncodeInto(data, data.length, rle);

        int max = C.maxCompressedLength(rleLen);
        byte[] out = ensure(COMP_BUF, 8 + max);

        writeInt(out, 0, data.length);
        writeInt(out, 4, rleLen);
        int n = C.compress(rle, 0, rleLen, out, 8, max);

        return Arrays.copyOf(out, 8 + n);
    }

    public static byte[] decompress(byte[] blob) {
        int origLen = readInt(blob, 0);
        int rleLen = readInt(blob, 4);

        byte[] rle = ensure(RLE_BUF, rleLen);
        D.decompress(blob, 8, rle, 0, rleLen);

        byte[] out = new byte[origLen];
        rleDecodeInto(rle, rleLen, out);
        return out;
    }

    private static void rleDecodeInto(byte[] rle, int rleLen, byte[] out) {
        int p = 0;
        for (int i = 0; i < rleLen; i += 2) {
            int run = rle[i] & 0xFF;
            byte v = rle[i + 1];
            Arrays.fill(out, p, p + run, v);
            p += run;
        }
    }

    private static void writeInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) (v);
    }

    private static int readInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }
}