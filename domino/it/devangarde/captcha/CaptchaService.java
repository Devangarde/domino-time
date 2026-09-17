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
            throw new IllegalStateException("Failed to initialize captcha key", e);
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
            throw new IllegalStateException("Error issuing captcha token", e);
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

    private byte[] renderImage(String text) throws IOException {
        int width = 140, height = 50;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);

        g.setColor(new Color(200, 200, 200));
        for (int i = 0; i < 6; i++) {
            g.drawLine(RNG.nextInt(width), RNG.nextInt(height), RNG.nextInt(width), RNG.nextInt(height));
        }

        Font font = new Font("SansSerif", Font.BOLD, 28);
        g.setFont(font);
        int x = 15;
        for (char c : text.toCharArray()) {
            double angle = (RNG.nextDouble() - 0.5) * 0.6;
            AffineTransform old = g.getTransform();
            g.setColor(new Color(30 + RNG.nextInt(90), 30 + RNG.nextInt(90), 30 + RNG.nextInt(90)));
            g.translate(x, 35 + RNG.nextInt(8));
            g.rotate(angle);
            g.drawString(String.valueOf(c), 0, 0);
            g.setTransform(old);
            x += 22;
        }

        g.dispose();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }
}
