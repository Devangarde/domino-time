package it.devangarde.captcha;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.SecretKey;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;

public class CaptchaService {

    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no ambiguous 0/O/1/I
    private static final int LENGTH = 5;
    private static final long TTL_MS = 5 * 60 * 1000; // 5 minutes

    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_LEN = 128;

    private static final SecureRandom RNG = new SecureRandom();

    private final SecretKey key;

    /** salt: the string you have in Security.CAPTCHA_SALT, of ANY length */
    public CaptchaService(String salt) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = sha256.digest(salt.getBytes(StandardCharsets.UTF_8)); // always 32 bytes
            this.key = new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("captchaKeyInitFailed", e);
        }
    }

    public static class Captcha {
        public final String imageBase64;
        public final String token;
        public final Date exp;
        Captcha(String imageBase64, String token, Date exp) {
            this.imageBase64 = imageBase64;
            this.token = token;
            this.exp = exp;
        }
    }

    /** The token is well-formed/signed but has expired */
    @SuppressWarnings("serial")
	public static class CaptchaExpiredException extends RuntimeException {
        public CaptchaExpiredException(String message) { super(message); }
    }

    /** Token missing, malformed, tampered with, or encrypted with a different key */
    @SuppressWarnings("serial")
	public static class CaptchaInvalidException extends RuntimeException {
        public CaptchaInvalidException(String message) { super(message); }
    }

    /** Generates random text, image, and encrypted token. Single entry point. */
    public Captcha generate() throws IOException {
        String text = randomText();
        byte[] png = renderImage(text);
        String imageB64 = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);

        long expiryMillis = System.currentTimeMillis() + TTL_MS;
        String token = issue(text, expiryMillis);

        return new Captcha(imageB64, token, new Date(expiryMillis));
    }

    /**
     * Verifies the user's answer against the received token.
     * Simply returns false on any problem (expired, tampered with, wrong answer).
     */
    public boolean verify(String token, String userAnswer) {
        try {
            String solution = decryptAndExtract(token);
            return userAnswer != null && solution.equalsIgnoreCase(userAnswer.trim());
        } catch (CaptchaExpiredException | CaptchaInvalidException e) {
            return false;
        }
    }

    // ---- cryptography ----

    /** expiryMillis: absolute expiry instant (epoch ms), already computed by the caller */
    private String issue(String text, long expiryMillis) {
        try {
            byte[] iv = new byte[IV_LEN];
            RNG.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LEN, iv));

            String payload = text + "|" + expiryMillis;
            byte[] ct = cipher.doFinal(payload.getBytes(StandardCharsets.UTF_8));

            return b64(iv) + "." + b64(ct);
        } catch (Exception e) {
            throw new IllegalStateException("captchaTokenIssueFailed", e);
        }
    }

    /**
     * Decrypts the token and returns the plaintext solution.
     * Throws CaptchaExpiredException if the token is valid but expired,
     * CaptchaInvalidException for any other problem (missing, malformed,
     * tampered with, or encrypted with a different key).
     */
    private String decryptAndExtract(String token) {
        if (token == null || token.isEmpty()) {
            throw new CaptchaInvalidException("Missing token");
        }
        int dot = token.indexOf('.');
        if (dot < 0) {
            throw new CaptchaInvalidException("Malformed token");
        }

        String text;
        long expiry;
        try {
            byte[] iv = Base64.getUrlDecoder().decode(token.substring(0, dot));
            byte[] ct = Base64.getUrlDecoder().decode(token.substring(dot + 1));

            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LEN, iv));
            byte[] plain = cipher.doFinal(ct); // throws if tampered with or wrong key

            String[] parts = new String(plain, StandardCharsets.UTF_8).split("\\|");
            text = parts[0];
            expiry = Long.parseLong(parts[1]);
        } catch (Exception e) {
            // any exception here (GCM auth tag check failed, invalid Base64,
            // parsing failed) means the token was tampered with or the key is wrong
            throw new CaptchaInvalidException("Invalid token");
        }

        if (System.currentTimeMillis() > expiry) {
            throw new CaptchaExpiredException("Captcha expired");
        }

        return text;
    }

    private String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    // ---- text and image generation ----

    private String randomText() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(CHARS.charAt(RNG.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    /**
     * Hand-drawn 5x7 dot-matrix glyphs for the exact CHARS alphabet, rendered
     * with plain Graphics2D.fillRect() only.
     *
     * WHY: java.awt.Font/drawString need the JVM's platform font subsystem
     * (on Linux, backed by native fontconfig) to resolve a logical family
     * like "SansSerif" to an actual font file. On a minimal/headless Linux
     * install without fontconfig + font packages, that resolution fails with
     * "Fontconfig head is null" — which is exactly what breaks this service
     * on Domino-on-Linux while working fine on Windows (which has its own,
     * always-present font resolution). fillRect() never touches that
     * subsystem at all, so this is identical on every platform/JVM with no
     * server-side font installation required.
     */
    private static final Map<Character, String[]> GLYPHS = buildGlyphs();

    private static Map<Character, String[]> buildGlyphs() {
        Map<Character, String[]> f = new HashMap<>();
        f.put('A', new String[]{".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"});
        f.put('B', new String[]{"####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."});
        f.put('C', new String[]{".####", "#....", "#....", "#....", "#....", "#....", ".####"});
        f.put('D', new String[]{"####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."});
        f.put('E', new String[]{"#####", "#....", "#....", "####.", "#....", "#....", "#####"});
        f.put('F', new String[]{"#####", "#....", "#....", "####.", "#....", "#....", "#...."});
        f.put('G', new String[]{".####", "#....", "#....", "#.###", "#...#", "#...#", ".####"});
        f.put('H', new String[]{"#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"});
        f.put('J', new String[]{"..###", "...#.", "...#.", "...#.", "...#.", "#..#.", ".##.."});
        f.put('K', new String[]{"#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"});
        f.put('L', new String[]{"#....", "#....", "#....", "#....", "#....", "#....", "#####"});
        f.put('M', new String[]{"#...#", "##.##", "#.#.#", "#...#", "#...#", "#...#", "#...#"});
        f.put('N', new String[]{"#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#", "#...#"});
        f.put('P', new String[]{"####.", "#...#", "#...#", "####.", "#....", "#....", "#...."});
        f.put('Q', new String[]{".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#"});
        f.put('R', new String[]{"####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"});
        f.put('S', new String[]{".####", "#....", "#....", ".###.", "....#", "....#", "####."});
        f.put('T', new String[]{"#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."});
        f.put('U', new String[]{"#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."});
        f.put('V', new String[]{"#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#.."});
        f.put('W', new String[]{"#...#", "#...#", "#...#", "#.#.#", "#.#.#", "#.#.#", ".#.#."});
        f.put('X', new String[]{"#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#"});
        f.put('Y', new String[]{"#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#.."});
        f.put('Z', new String[]{"#####", "....#", "...#.", "..#..", ".#...", "#....", "#####"});
        f.put('2', new String[]{".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"});
        f.put('3', new String[]{".###.", "#...#", "....#", "..##.", "....#", "#...#", ".###."});
        f.put('4', new String[]{"...##", "..#.#", ".#..#", "#...#", "#####", "....#", "....#"});
        f.put('5', new String[]{"#####", "#....", "#....", "####.", "....#", "#...#", ".###."});
        f.put('6', new String[]{"..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."});
        f.put('7', new String[]{"#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."});
        f.put('8', new String[]{".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."});
        f.put('9', new String[]{".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."});
        return f;
    }

    private byte[] renderImage(String text) throws IOException {
        int cell = 6;                  // px per dot
        int glyphW = 5, glyphH = 7;    // dots
        int glyphGap = 12;             // px between glyphs
        int margin = 16;               // px

        int width = margin * 2 + text.length() * (glyphW * cell) + (text.length() - 1) * glyphGap;
        int height = margin * 2 + glyphH * cell + 10; // a bit of slack for per-char vertical jitter

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);

        g.setColor(new Color(200, 200, 200));
        for (int i = 0; i < 6; i++) {
            g.drawLine(RNG.nextInt(width), RNG.nextInt(height), RNG.nextInt(width), RNG.nextInt(height));
        }

        int x = margin;
        int y = margin;
        for (char c : text.toCharArray()) {
            String[] rows = GLYPHS.get(c);
            double angle = (RNG.nextDouble() - 0.5) * 0.35;
            int jitterY = RNG.nextInt(4) - 2;

            AffineTransform old = g.getTransform();
            g.setColor(new Color(30 + RNG.nextInt(90), 30 + RNG.nextInt(90), 30 + RNG.nextInt(90)));
            g.translate(x + (glyphW * cell) / 2.0, y + jitterY + (glyphH * cell) / 2.0);
            g.rotate(angle);
            g.translate(-(glyphW * cell) / 2.0, -(glyphH * cell) / 2.0);
            for (int r = 0; r < glyphH; r++) {
                for (int col = 0; col < glyphW; col++) {
                    if (rows[r].charAt(col) == '#') {
                        g.fillRect(col * cell, r * cell, cell - 1, cell - 1);
                    }
                }
            }
            g.setTransform(old);

            x += glyphW * cell + glyphGap;
        }

        g.dispose();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }
}
