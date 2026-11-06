package com.mbnetwork.filemanager;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Opens password-protected Word / Excel / PowerPoint files (Office 2010+ "Agile" encryption).
 * Such a file is an old-style OLE container that holds an encrypted copy of the real .docx / .xlsx / .pptx.
 * Everything runs on the phone.
 */
public final class OfficeCrypt {
    private OfficeCrypt() { }

    public static class WrongPassword extends IOException {
        public WrongPassword() { super("Wrong password"); }
    }

    public static boolean isEncrypted(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] h = new byte[8];
            if (in.read(h) != 8) return false;
            return (h[0] & 0xFF) == 0xD0 && (h[1] & 0xFF) == 0xCF && (h[2] & 0xFF) == 0x11 && (h[3] & 0xFF) == 0xE0;
        } catch (IOException e) { return false; }
    }

    // ------------------------------------------------------------------ OLE container

    private static final class Cfb {
        final byte[] d;
        final int sectorSize, miniSize, miniCutoff;
        final int[] fat;
        int[] miniFat = new int[0];
        byte[] miniStream = new byte[0];
        final java.util.Map<String, long[]> entries = new java.util.HashMap<>();   // name -> {start, size}

        Cfb(byte[] data) throws IOException {
            d = data;
            if (d.length < 512) throw new IOException("Not an OLE file");
            sectorSize = 1 << u16(0x1E);
            miniSize = 1 << u16(0x20);
            miniCutoff = (int) u32(0x38);
            int numFat = (int) u32(0x2C);
            int dirStart = (int) u32(0x30);
            int miniFatStart = (int) u32(0x3C);
            int numMiniFat = (int) u32(0x40);
            int difatStart = (int) u32(0x44);
            int numDifat = (int) u32(0x48);

            int[] fatSectors = new int[numFat];
            int n = 0;
            for (int i = 0; i < 109 && n < numFat; i++) fatSectors[n++] = (int) u32(0x4C + 4 * i);
            int ds = difatStart;
            for (int k = 0; k < numDifat && n < numFat && ds >= 0; k++) {
                int off = off(ds);
                for (int i = 0; i < sectorSize / 4 - 1 && n < numFat; i++) fatSectors[n++] = (int) u32(off + 4 * i);
                ds = (int) u32(off + sectorSize - 4);
            }
            int per = sectorSize / 4;
            fat = new int[numFat * per];
            for (int i = 0; i < numFat; i++) {
                int off = off(fatSectors[i]);
                for (int j = 0; j < per; j++) fat[i * per + j] = (int) u32(off + 4 * j);
            }
            byte[] dir = chain(dirStart, Long.MAX_VALUE);
            long rootStart = -1, rootSize = 0;
            for (int p = 0; p + 128 <= dir.length; p += 128) {
                int type = dir[p + 0x42] & 0xFF;
                if (type == 0) continue;
                int nameLen = (dir[p + 0x40] & 0xFF) | ((dir[p + 0x41] & 0xFF) << 8);
                String name = new String(dir, p, Math.max(0, nameLen - 2), Charset.forName("UTF-16LE"));
                long start = le32(dir, p + 0x74) & 0xFFFFFFFFL;
                long size = le32(dir, p + 0x78) & 0xFFFFFFFFL;
                if (type == 5) { rootStart = start; rootSize = size; }
                else entries.put(name, new long[]{start, size});
            }
            if (miniFatStart >= 0 && numMiniFat > 0) {
                byte[] mf = chain(miniFatStart, Long.MAX_VALUE);
                miniFat = new int[mf.length / 4];
                for (int i = 0; i < miniFat.length; i++) miniFat[i] = le32(mf, 4 * i);
            }
            if (rootStart >= 0) miniStream = chain((int) rootStart, rootSize);
        }

        int u16(int o) { return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8); }
        long u32(int o) { return le32(d, o); }
        static int le32(byte[] b, int o) {
            return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
        }
        int off(int sector) { return (sector + 1) * sectorSize; }

        byte[] chain(int start, long limit) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int s = start, guard = 0;
            while (s >= 0 && s < fat.length && out.size() < limit && guard++ < fat.length + 1) {
                int o = off(s);
                if (o + sectorSize > d.length) { out.write(d, o, Math.max(0, d.length - o)); break; }
                out.write(d, o, sectorSize);
                s = fat[s];
            }
            byte[] all = out.toByteArray();
            return limit < all.length ? Arrays.copyOf(all, (int) limit) : all;
        }

        byte[] stream(String name) throws IOException {
            long[] e = entries.get(name);
            if (e == null) throw new IOException("Missing " + name);
            if (e[1] >= miniCutoff) return chain((int) e[0], e[1]);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int s = (int) e[0], guard = 0;
            while (s >= 0 && s < miniFat.length && out.size() < e[1] && guard++ < miniFat.length + 1) {
                int o = s * miniSize;
                out.write(miniStream, o, Math.min(miniSize, miniStream.length - o));
                s = miniFat[s];
            }
            byte[] all = out.toByteArray();
            return e[1] < all.length ? Arrays.copyOf(all, (int) e[1]) : all;
        }
    }

    // ------------------------------------------------------------------ Agile decryption

    private static byte[] hash(String alg, byte[]... parts) throws Exception {
        MessageDigest md = MessageDigest.getInstance(alg);
        for (byte[] p : parts) md.update(p);
        return md.digest();
    }

    private static String javaHash(String name) {
        switch (name.toUpperCase()) {
            case "SHA1": case "SHA-1": return "SHA-1";
            case "SHA256": return "SHA-256";
            case "SHA384": return "SHA-384";
            default: return "SHA-512";
        }
    }

    private static byte[] fit(byte[] b, int len) {
        if (b.length == len) return b;
        byte[] r = new byte[len];
        Arrays.fill(r, (byte) 0x36);
        System.arraycopy(b, 0, r, 0, Math.min(len, b.length));
        return r;
    }

    private static byte[] aes(boolean decrypt, byte[] key, byte[] iv, byte[] data) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
        c.init(decrypt ? Cipher.DECRYPT_MODE : Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return c.doFinal(data);
    }

    private static Element first(Element root, String local) {
        NodeList l = root.getElementsByTagNameNS("*", local);
        return l.getLength() == 0 ? null : (Element) l.item(0);
    }

    private static byte[] b64(String s) { return android.util.Base64.decode(s, android.util.Base64.DEFAULT); }

    /** Returns the plain .docx / .xlsx / .pptx bytes, or throws [WrongPassword]. */
    public static byte[] decrypt(File f, String password) throws Exception {
        byte[] all;
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[65536]; int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            all = bo.toByteArray();
        }
        Cfb cfb = new Cfb(all);
        byte[] info = cfb.stream("EncryptionInfo");
        int major = (info[0] & 0xFF) | ((info[1] & 0xFF) << 8);
        int minor = (info[2] & 0xFF) | ((info[3] & 0xFF) << 8);
        if (!(major == 4 && minor == 4)) throw new IOException("Unsupported encryption (only Office 2010+ passwords)");
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        Element root = dbf.newDocumentBuilder()
                .parse(new ByteArrayInputStream(info, 8, info.length - 8)).getDocumentElement();
        Element keyData = first(root, "keyData");
        Element ek = first(root, "encryptedKey");
        if (keyData == null || ek == null) throw new IOException("Bad encryption info");

        byte[] salt = b64(ek.getAttribute("saltValue"));
        String alg = javaHash(ek.getAttribute("hashAlgorithm"));
        int spin = Integer.parseInt(ek.getAttribute("spinCount"));
        int keyBits = Integer.parseInt(ek.getAttribute("keyBits"));
        int blockSize = Integer.parseInt(ek.getAttribute("blockSize"));
        byte[] encKey = b64(ek.getAttribute("encryptedKeyValue"));

        byte[] h = hash(alg, salt, password.getBytes("UTF-16LE"));
        for (int i = 0; i < spin; i++) {
            byte[] it = new byte[]{(byte) i, (byte) (i >> 8), (byte) (i >> 16), (byte) (i >> 24)};
            h = hash(alg, it, h);
        }
        byte[] keyBlock = {(byte) 0x14, 0x6e, 0x0b, (byte) 0xe7, (byte) 0xab, (byte) 0xac, (byte) 0xd0, (byte) 0xd6};
        byte[] k = fit(hash(alg, h, keyBlock), keyBits / 8);
        byte[] key = Arrays.copyOf(aes(true, k, fit(salt, blockSize), encKey), keyBits / 8);

        // password check: the verifier hash must match
        Element ev = ek;
        byte[] vInBlock = {(byte) 0xfe, (byte) 0xa7, (byte) 0xd2, 0x76, 0x3b, 0x4b, (byte) 0x9e, 0x79};
        byte[] vValBlock = {(byte) 0xd7, (byte) 0xaa, 0x0f, 0x6d, 0x30, 0x61, 0x34, 0x4e};
        byte[] vIn = aes(true, fit(hash(alg, h, vInBlock), keyBits / 8), fit(salt, blockSize), b64(ev.getAttribute("encryptedVerifierHashInput")));
        byte[] vVal = aes(true, fit(hash(alg, h, vValBlock), keyBits / 8), fit(salt, blockSize), b64(ev.getAttribute("encryptedVerifierHashValue")));
        byte[] want = hash(alg, Arrays.copyOf(vIn, Integer.parseInt(ev.getAttribute("saltSize"))));
        byte[] got = Arrays.copyOf(vVal, want.length);
        if (!Arrays.equals(want, got)) throw new WrongPassword();

        byte[] dSalt = b64(keyData.getAttribute("saltValue"));
        String dAlg = javaHash(keyData.getAttribute("hashAlgorithm"));
        int dBlock = Integer.parseInt(keyData.getAttribute("blockSize"));

        byte[] pkg = cfb.stream("EncryptedPackage");
        long size = 0;
        for (int i = 7; i >= 0; i--) size = (size << 8) | (pkg[i] & 0xFF);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int seg = 0;
        for (int p = 8; p < pkg.length; p += 4096, seg++) {
            int len = Math.min(4096, pkg.length - p);
            len -= len % 16;
            if (len <= 0) break;
            byte[] segIdx = {(byte) seg, (byte) (seg >> 8), (byte) (seg >> 16), (byte) (seg >> 24)};
            byte[] iv = fit(hash(dAlg, dSalt, segIdx), dBlock);
            out.write(aes(true, key, iv, Arrays.copyOfRange(pkg, p, p + len)));
        }
        byte[] res = out.toByteArray();
        return size < res.length ? Arrays.copyOf(res, (int) size) : res;
    }
}
