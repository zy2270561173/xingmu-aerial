package app.xingmu.aerial.cast;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

public final class LegacyAdbClient {
    private static final String TAG = "StarlightCapture";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int A_SYNC = command("SYNC");
    private static final int A_CNXN = command("CNXN");
    private static final int A_OPEN = command("OPEN");
    private static final int A_OKAY = command("OKAY");
    private static final int A_CLSE = command("CLSE");
    private static final int A_WRTE = command("WRTE");
    private static final int A_AUTH = command("AUTH");
    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;
    private static final int ADB_VERSION = 0x01000000;
    private static final int MAX_DATA = 4096;
    private static final int MAX_PACKET = 16 * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 1800;
    private static final int IO_TIMEOUT_MS = 5000;
    private static final int INSTALL_TIMEOUT_MS = 120_000;
    private static final int AUTHORIZATION_TIMEOUT_MS = 15_000;
    private static final long STATUS_CACHE_MS = 5 * 60_000L;
    private static final String PREFS = "tv_cast_adb_identity";
    private static final String PRIVATE_KEY = "private_key";
    private static final String PUBLIC_KEY = "public_key";
    private static final byte[] SHA1_DIGEST_INFO = hex("3021300906052b0e03021a05000414");
    private static final Object CONNECTION_LOCK = new Object();

    private static volatile String cachedStatus = "untested";
    private static volatile long cachedAt;
    private static volatile long keepAlivePolicyAt;
    private static volatile long lastCaptureLogAt;
    private static Connection sharedConnection;
    private static int sharedPort = -1;

    private LegacyAdbClient() {
    }

    public static Result test(Context context, int port) {
        ShellResult probe = shell(context, port, "echo starlight-adb", 1024);
        String status = cachedStatus;
        if (probe.success) {
            return Result.success("connected", "ADB TCP 已自动连接并授权：127.0.0.1:" + port);
        }
        if ("authorization_required".equals(status)) {
            return Result.failure(status, "已连接 ADB，请在电视确认这台设备的 RSA 调试授权后重试");
        }
        if ("unsupported".equals(status)) {
            return Result.failure(status, "该端口不是经典 ADB TCP 连接端口；Android 11+ 配对码端口不能仅靠端口连接");
        }
        if ("unreachable".equals(status)) {
            return Result.failure(status, "无法连接 127.0.0.1:" + port + "，请先在系统或工程菜单启用 ADB TCP");
        }
        return Result.failure("error", "ADB 身份或握手失败");
    }

    public static boolean sendKey(Context context, int port, int keyCode) {
        return keyCode > 0 && shell(context, port, "input keyevent " + keyCode, 64 * 1024).success;
    }

    /** Starts a component through adbd: the reliable way to wake a TV app from the background. */
    public static boolean amStart(Context context, int port, String component) {
        if (component == null || !component.matches("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")) return false;
        ShellResult result = shell(context, port, "am start -n " + component, 8 * 1024);
        return result.success && !result.output.contains("Error");
    }

    /** Foreground package as adbd sees it, or "" when the query fails. */
    public static String currentPackage(Context context, int port) {
        ShellResult result = shell(context, port,
                "dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp'", 8 * 1024);
        if (!result.success) return "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("([a-z][a-z0-9_]+(?:\\.[a-z0-9_]+)+)/")
                .matcher(result.output);
        return matcher.find() ? matcher.group(1) : "";
    }
    /** Cheap liveness probe: true when adbd answers a trivial shell command. */
    public static boolean probe(Context context, int port) {
        ShellResult result = shell(context, port, "echo starlight", 1024, 4000);
        return result.success && result.output.contains("starlight");
    }
    public static boolean launchPackage(Context context, int port, String packageName) {
        if (packageName == null || !packageName.matches("[A-Za-z0-9_.-]{1,255}")) return false;
        ShellResult result = shell(context, port,
                "monkey -p " + packageName + " -c android.intent.category.LAUNCHER 1", 64 * 1024);
        return result.success && !result.output.contains("No activities found");
    }

    public static InstallResult installPackage(Context context, int port, File apk) {
        if (apk == null || !apk.isFile() || apk.length() <= 0L) {
            return InstallResult.failure(false, "APK 文件不存在");
        }
        String remote = "/data/local/tmp/starlight-install-"
                + SystemClock.elapsedRealtime() + ".apk";
        String source = shellQuote(apk.getAbsolutePath());
        String target = shellQuote(remote);
        String command = "if cp " + source + " " + target + "; then "
                + "chmod 644 " + target + "; pm install -r " + target + "; code=$?; "
                + "else echo starlight-copy-failed; code=125; fi; "
                + "rm -f " + target + "; echo __STARLIGHT_EXIT__${code}";
        ShellResult result = shell(context, port, command, 128 * 1024, INSTALL_TIMEOUT_MS);
        if (!result.success) {
            return InstallResult.failure(false, "ADB 安装通道中断，将改用电视系统安装器");
        }
        String output = result.output == null ? "" : result.output.trim();
        int exitCode = parseExitCode(output);
        if (exitCode == 0 && output.contains("Success")) {
            return InstallResult.success("ADB 已完成安装");
        }
        if (exitCode == 125 || output.contains("starlight-copy-failed")) {
            return InstallResult.failure(false, "ADB 无法读取下载文件，将改用电视系统安装器");
        }
        return InstallResult.failure(true, installFailureMessage(output));
    }

    public static boolean shutdown(Context context, int port) {
        return shell(context, port,
                "svc power shutdown || reboot -p", 64 * 1024).success;
    }

    public static Bitmap captureScreen(Context context, int port) {
        long startedAt = SystemClock.elapsedRealtime();
        Bitmap fileCapture = captureScreenToFile(context, port);
        if (fileCapture != null) return reportCapture("adb_file", fileCapture, startedAt);
        // base64 first: raw "screencap -p" output loses bytes when the shell rewrites newlines,
        // which decodes into a picture that is only correct for the first few rows.
        Bitmap base64Capture = captureBase64Screen(context, port);
        if (base64Capture != null) return reportCapture("adb_b64", base64Capture, startedAt);
        Bitmap rawCapture = captureRawFramebuffer(context, port);
        if (rawCapture != null) return reportCapture("adb_raw", rawCapture, startedAt);
        reportCapture("adb_failed", null, startedAt);
        return null;
    }

    private static Bitmap captureRawFramebuffer(Context context, int port) {
        ShellResult result = shell(context, port, "screencap", 40 * 1024 * 1024);
        return result.success ? decodeRawFramebuffer(result.bytes) : null;
    }

    private static Bitmap capturePngScreen(Context context, int port) {
        ShellResult result = shell(context, port, "screencap -p", 12 * 1024 * 1024);
        if (!result.success || result.bytes.length < 8) return null;
        Bitmap decoded = decodeScreenshot(result.bytes);
        if (decoded != null) return decoded;
        byte[] normalized = normalizeLegacyShellNewlines(result.bytes);
        return normalized == result.bytes ? null : decodeScreenshot(normalized);
    }

    private static Bitmap captureBase64Screen(Context context, int port) {
        ShellResult result = shell(context, port, "screencap -p | base64", 20 * 1024 * 1024);
        if (!result.success || result.bytes.length < 12) return null;
        try {
            return decodeScreenshot(Base64.decode(result.bytes, Base64.DEFAULT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static Bitmap reportCapture(String mode, Bitmap bitmap, long startedAt) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastCaptureLogAt >= 5_000L) {
            lastCaptureLogAt = now;
            String size = bitmap == null ? "none" : bitmap.getWidth() + "x" + bitmap.getHeight();
            Log.i(TAG, mode + " " + size + " in " + (now - startedAt) + " ms");
        }
        return bitmap;
    }

    private static Bitmap captureScreenToFile(Context context, int port) {
        File directory = context.getExternalCacheDir();
        if (directory == null) directory = context.getCacheDir();
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) return null;
        File output = new File(directory, "adb-screen.png");
        String path = output.getAbsolutePath().replace("'", "'\\''");
        String capture = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? "timeout 3 screencap -p '" + path + "'"
                : "screencap -p '" + path + "'";
        ShellResult command = shell(context, port,
                "rm -f '" + path + "' && " + capture, 16 * 1024);
        if (!command.success || !output.isFile() || output.length() < 8L) {
            return null;
        }
        try {
            return BitmapFactory.decodeFile(output.getAbsolutePath());
        } catch (RuntimeException | OutOfMemoryError ignored) {
            return null;
        }
    }

    private static Bitmap decodeRawFramebuffer(byte[] bytes) {
        if (bytes == null || bytes.length < 16) return null;
        int width = littleEndianInt(bytes, 0);
        int height = littleEndianInt(bytes, 4);
        int format = littleEndianInt(bytes, 8);
        if (width <= 0 || height <= 0 || width > 8192 || height > 8192) return null;
        int bytesPerPixel = format == 3 ? 3 : format == 4 ? 2 : 4;
        long pixelBytes = (long) width * height * bytesPerPixel;
        if (pixelBytes <= 0 || pixelBytes > Integer.MAX_VALUE) return null;
        int offset;
        if (bytes.length >= pixelBytes + 16L) offset = 16;
        else if (bytes.length >= pixelBytes + 12L) offset = 12;
        else return null;
        try {
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            int[] row = new int[width];
            for (int y = 0; y < height; y++) {
                int source = offset + y * width * bytesPerPixel;
                for (int x = 0; x < width; x++, source += bytesPerPixel) {
                    int red;
                    int green;
                    int blue;
                    int alpha = 255;
                    if (format == 4) {
                        int packed = (bytes[source] & 0xff) | ((bytes[source + 1] & 0xff) << 8);
                        red = ((packed >> 11) & 0x1f) * 255 / 31;
                        green = ((packed >> 5) & 0x3f) * 255 / 63;
                        blue = (packed & 0x1f) * 255 / 31;
                    } else {
                        red = bytes[source] & 0xff;
                        green = bytes[source + 1] & 0xff;
                        blue = bytes[source + 2] & 0xff;
                        if (format == 1 && bytesPerPixel == 4) alpha = bytes[source + 3] & 0xff;
                    }
                    row[x] = (alpha << 24) | (red << 16) | (green << 8) | blue;
                }
                bitmap.setPixels(row, 0, width, 0, y, width, 1);
            }
            return bitmap;
        } catch (RuntimeException | OutOfMemoryError ignored) {
            return null;
        }
    }

    private static int littleEndianInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16)
                | ((bytes[offset + 3] & 0xff) << 24);
    }

    private static Bitmap decodeScreenshot(byte[] bytes) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            int sample = 1;
            while (bounds.outWidth / sample > 2560 || bounds.outHeight / sample > 1440) sample *= 2;
            options.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (RuntimeException | OutOfMemoryError ignored) {
            return null;
        }
    }

    private static byte[] normalizeLegacyShellNewlines(byte[] source) {
        int pairs = 0;
        for (int index = 0; index + 1 < source.length; index++) {
            if (source[index] == '\r' && source[index + 1] == '\n') pairs++;
        }
        if (pairs == 0) return source;
        ByteArrayOutputStream cleaned = new ByteArrayOutputStream(source.length - pairs);
        for (int index = 0; index < source.length; index++) {
            if (source[index] == '\r' && index + 1 < source.length && source[index + 1] == '\n') {
                continue;
            }
            cleaned.write(source[index]);
        }
        return cleaned.toByteArray();
    }

    public static String cachedStatus() {
        if (SystemClock.elapsedRealtime() - cachedAt > STATUS_CACHE_MS) return "untested";
        return cachedStatus;
    }

    public static boolean shouldAttempt() {
        String status = cachedStatus();
        return "untested".equals(status) || "connected".equals(status);
    }

    public static boolean applyKeepAlivePolicy(Context context, int port) {
        long now = SystemClock.elapsedRealtime();
        if (keepAlivePolicyAt > 0L && now - keepAlivePolicyAt < 30 * 60_000L) return true;
        String packageName = context.getPackageName();
        StringBuilder command = new StringBuilder("pm enable ")
                .append(packageName).append(" >/dev/null 2>&1; ");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            command.append("am set-inactive ").append(packageName)
                    .append(" false >/dev/null 2>&1; ")
                    .append("cmd deviceidle whitelist +").append(packageName)
                    .append(" >/dev/null 2>&1; ")
                    .append("cmd appops set ").append(packageName)
                    .append(" RUN_IN_BACKGROUND allow >/dev/null 2>&1; ")
                    .append("cmd appops set ").append(packageName)
                    .append(" RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1; ");
        }
        command.append("nemu_auto=$(settings get global nemuAutoApps 2>/dev/null); ")
                .append("if [ \"$nemu_auto\" != \"null\" ]; then ")
                .append("if [ -z \"$nemu_auto\" ]; then settings put global nemuAutoApps ")
                .append(packageName).append(" >/dev/null 2>&1; else ")
                .append("case \";$nemu_auto;\" in *\";").append(packageName)
                .append(";\"*) ;; *) settings put global nemuAutoApps ")
                .append("\"$nemu_auto;").append(packageName)
                .append("\" >/dev/null 2>&1 ;; esac; fi; fi; ");
        command.append("if [ \"$(id -u)\" = \"0\" ] && [ -d /data/adb ]; then ")
                .append("mkdir -p /data/adb/service.d >/dev/null 2>&1; ")
                .append("printf '%s\\n' '#!/system/bin/sh' ")
                .append("'until [ \"$(getprop sys.boot_completed)\" = \"1\" ]; do sleep 2; done' ")
                .append("'sleep 5' 'sdk=$(getprop ro.build.version.sdk)' ")
                .append("'if [ \"$sdk\" -ge 26 ]; then' ")
                .append("'am start-foreground-service --user 0 -n ").append(packageName)
                .append("/com.starlight.tvcast.service.KeepAliveService >/dev/null 2>&1' ")
                .append("'else' 'am startservice --user 0 -n ").append(packageName)
                .append("/com.starlight.tvcast.service.KeepAliveService >/dev/null 2>&1' 'fi' ")
                .append("> /data/adb/service.d/starlight-tvcast.sh; ")
                .append("chmod 755 /data/adb/service.d/starlight-tvcast.sh; fi; ");
        command.append("echo starlight-keepalive");
        ShellResult result = shell(context, port, command.toString(), 64 * 1024);
        boolean applied = result.success && result.output.contains("starlight-keepalive");
        Log.i(TAG, "keepalive_policy " + (applied ? "applied" : "failed")
                + " adb_status=" + cachedStatus);
        if (applied) keepAlivePolicyAt = now;
        return applied;
    }

    public static boolean isKeepAlivePolicyApplied() {
        return keepAlivePolicyAt > 0L;
    }

    private static ShellResult shell(Context context, int port, String command, int maxBytes) {
        return shell(context, port, command, maxBytes, IO_TIMEOUT_MS);
    }

    private static ShellResult shell(Context context, int port, String command,
                                     int maxBytes, int timeoutMs) {
        synchronized (CONNECTION_LOCK) {
            try {
                Connection connection = shared(context, port);
                byte[] output;
                try {
                    connection.setReadTimeout(timeoutMs);
                    try {
                        output = connection.shell(command, maxBytes);
                    } finally {
                        connection.setReadTimeout(IO_TIMEOUT_MS);
                    }
                } catch (Exception firstFailure) {
                    resetShared();
                    Connection retry = shared(context, port);
                    retry.setReadTimeout(timeoutMs);
                    try {
                        output = retry.shell(command, maxBytes);
                    } finally {
                        retry.setReadTimeout(IO_TIMEOUT_MS);
                    }
                }
                cache("connected");
                return new ShellResult(true, output);
            } catch (AuthorizationRequired error) {
                resetShared();
                cache("authorization_required");
                return new ShellResult(false, new byte[0]);
            } catch (UnsupportedProtocol error) {
                resetShared();
                cache("unsupported");
                return new ShellResult(false, new byte[0]);
            } catch (IOException error) {
                resetShared();
                cache("unreachable");
                return new ShellResult(false, new byte[0]);
            } catch (Exception error) {
                resetShared();
                cache("error");
                return new ShellResult(false, new byte[0]);
            }
        }
    }

    private static Connection shared(Context context, int port) throws Exception {
        if (sharedConnection == null || sharedPort != port) {
            resetShared();
            sharedConnection = connect(context, port);
            sharedPort = port;
        }
        return sharedConnection;
    }

    private static void resetShared() {
        close(sharedConnection);
        sharedConnection = null;
        sharedPort = -1;
    }

    private static Connection connect(Context context, int port) throws Exception {
        if (port < 1024 || port > 65535) throw new IOException("invalid port");
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(IO_TIMEOUT_MS);
            Connection connection = new Connection(socket);
            connection.write(A_CNXN, ADB_VERSION, MAX_DATA,
                    "host::features=shell_v2,cmd\0".getBytes(UTF8));
            boolean signatureSent = false;
            boolean publicKeySent = false;
            for (int attempt = 0; attempt < 8; attempt++) {
                Packet packet;
                try {
                    packet = connection.read();
                } catch (SocketTimeoutException timeout) {
                    if (publicKeySent) throw new AuthorizationRequired();
                    throw timeout;
                }
                if (packet.command == A_CNXN) {
                    socket.setSoTimeout(IO_TIMEOUT_MS);
                    return connection;
                }
                if (packet.command != A_AUTH || packet.arg0 != AUTH_TOKEN || packet.data.length != 20) {
                    throw new UnsupportedProtocol();
                }
                KeyPair identity = identity(context);
                if (!signatureSent) {
                    connection.write(A_AUTH, AUTH_SIGNATURE, 0,
                            signToken(identity.getPrivate(), packet.data));
                    signatureSent = true;
                } else if (!publicKeySent) {
                    connection.write(A_AUTH, AUTH_RSAPUBLICKEY, 0,
                            adbPublicKey(identity.getPublic()));
                    publicKeySent = true;
                    socket.setSoTimeout(AUTHORIZATION_TIMEOUT_MS);
                } else {
                    throw new AuthorizationRequired();
                }
            }
            throw new AuthorizationRequired();
        } catch (Exception error) {
            try { socket.close(); } catch (IOException ignored) { }
            throw error;
        }
    }

    private static synchronized KeyPair identity(Context context) throws Exception {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String privateValue = preferences.getString(PRIVATE_KEY, "");
        String publicValue = preferences.getString(PUBLIC_KEY, "");
        if (privateValue != null && !privateValue.isEmpty()
                && publicValue != null && !publicValue.isEmpty()) {
            try {
                KeyFactory factory = KeyFactory.getInstance("RSA");
                PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(
                        Base64.decode(privateValue, Base64.DEFAULT)));
                PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(
                        Base64.decode(publicValue, Base64.DEFAULT)));
                return new KeyPair(publicKey, privateKey);
            } catch (Exception ignored) {
                preferences.edit().clear().commit();
            }
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair generated = generator.generateKeyPair();
        preferences.edit()
                .putString(PRIVATE_KEY, Base64.encodeToString(
                        generated.getPrivate().getEncoded(), Base64.NO_WRAP))
                .putString(PUBLIC_KEY, Base64.encodeToString(
                        generated.getPublic().getEncoded(), Base64.NO_WRAP))
                .commit();
        return generated;
    }

    private static byte[] signToken(PrivateKey privateKey, byte[] token) throws Exception {
        byte[] digestInfo = new byte[SHA1_DIGEST_INFO.length + token.length];
        System.arraycopy(SHA1_DIGEST_INFO, 0, digestInfo, 0, SHA1_DIGEST_INFO.length);
        System.arraycopy(token, 0, digestInfo, SHA1_DIGEST_INFO.length, token.length);
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, privateKey);
        return cipher.doFinal(digestInfo);
    }

    private static byte[] adbPublicKey(PublicKey publicKey) throws Exception {
        RSAPublicKey rsa = (RSAPublicKey) publicKey;
        BigInteger modulus = rsa.getModulus();
        BigInteger two32 = BigInteger.ONE.shiftLeft(32);
        BigInteger mask32 = two32.subtract(BigInteger.ONE);
        long n0inv = two32.subtract(modulus.and(mask32).modInverse(two32))
                .and(mask32).longValue();
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(modulus);
        ByteArrayOutputStream raw = new ByteArrayOutputStream(524);
        writeInt(raw, 64);
        writeInt(raw, (int) n0inv);
        raw.write(fixedLittleEndian(modulus, 256));
        raw.write(fixedLittleEndian(rr, 256));
        writeInt(raw, rsa.getPublicExponent().intValue());
        String encoded = Base64.encodeToString(raw.toByteArray(), Base64.NO_WRAP)
                + " starlight@tv\0";
        return encoded.getBytes(UTF8);
    }

    private static byte[] fixedLittleEndian(BigInteger value, int length) {
        byte[] big = value.toByteArray();
        byte[] little = new byte[length];
        for (int index = 0; index < length; index++) {
            int source = big.length - 1 - index;
            little[index] = source >= 0 ? big[source] : 0;
        }
        return little;
    }

    private static int command(String value) {
        return value.charAt(0) | value.charAt(1) << 8 | value.charAt(2) << 16 | value.charAt(3) << 24;
    }

    private static int checksum(byte[] data) {
        int sum = 0;
        for (byte value : data) sum += value & 0xff;
        return sum;
    }

    private static byte[] hex(String value) {
        byte[] output = new byte[value.length() / 2];
        for (int index = 0; index < output.length; index++) {
            output[index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
        }
        return output;
    }

    private static void writeInt(OutputStream output, int value) throws IOException {
        output.write(value & 0xff);
        output.write(value >>> 8 & 0xff);
        output.write(value >>> 16 & 0xff);
        output.write(value >>> 24 & 0xff);
    }

    private static int readInt(InputStream input) throws IOException {
        int a = input.read();
        int b = input.read();
        int c = input.read();
        int d = input.read();
        if ((a | b | c | d) < 0) throw new EOFException();
        return a | b << 8 | c << 16 | d << 24;
    }

    private static byte[] readExact(InputStream input, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(data, offset, length - offset);
            if (read < 0) throw new EOFException();
            offset += read;
        }
        return data;
    }

    private static void cache(String status) {
        cachedStatus = status;
        cachedAt = SystemClock.elapsedRealtime();
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static int parseExitCode(String output) {
        String marker = "__STARLIGHT_EXIT__";
        int position = output.lastIndexOf(marker);
        if (position < 0) return -1;
        int start = position + marker.length();
        int end = start;
        while (end < output.length() && Character.isDigit(output.charAt(end))) end++;
        if (end == start) return -1;
        try {
            return Integer.parseInt(output.substring(start, end));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String installFailureMessage(String output) {
        if (output.contains("INSTALL_FAILED_OLDER_SDK")) {
            return "安装包要求的 Android 版本高于这台电视";
        }
        if (output.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE")) {
            return "电视中已有同包名但签名不同的版本，请先卸载旧版";
        }
        if (output.contains("INSTALL_FAILED_VERSION_DOWNGRADE")) {
            return "电视中的应用版本更高，系统拒绝降级安装";
        }
        if (output.contains("INSTALL_FAILED_NO_MATCHING_ABIS")) {
            return "安装包不包含这台电视 CPU 可用的版本";
        }
        if (output.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE")) {
            return "电视存储空间不足";
        }
        if (output.contains("INSTALL_FAILED_USER_RESTRICTED")
                || output.contains("INSTALL_FAILED_BLOCKED_BY_POLICY")) {
            return "电视系统策略禁止安装应用";
        }
        if (output.contains("INSTALL_PARSE_FAILED") || output.contains("INVALID_APK")) {
            return "电视系统无法解析此安装包";
        }
        int failure = output.indexOf("Failure [");
        if (failure >= 0) {
            int end = output.indexOf(']', failure);
            if (end > failure) return "安装失败：" + output.substring(failure + 9, end);
        }
        return "ADB 安装失败，电视系统未返回可识别的原因";
    }

    private static void close(Connection connection) {
        if (connection != null) connection.close();
    }

    private static final class Packet {
        final int command;
        final int arg0;
        final int arg1;
        final byte[] data;

        Packet(int command, int arg0, int arg1, byte[] data) {
            this.command = command;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.data = data;
        }
    }

    private static final class Connection {
        private final Socket socket;
        private final InputStream input;
        private final OutputStream output;
        private int nextLocalId = 1;

        Connection(Socket socket) throws IOException {
            this.socket = socket;
            this.input = socket.getInputStream();
            this.output = socket.getOutputStream();
        }

        void setReadTimeout(int timeoutMs) throws IOException {
            socket.setSoTimeout(timeoutMs);
        }

        void write(int command, int arg0, int arg1, byte[] data) throws IOException {
            byte[] payload = data == null ? new byte[0] : data;
            writeInt(output, command);
            writeInt(output, arg0);
            writeInt(output, arg1);
            writeInt(output, payload.length);
            writeInt(output, checksum(payload));
            writeInt(output, command ^ 0xffffffff);
            output.write(payload);
            output.flush();
        }

        Packet read() throws IOException, UnsupportedProtocol {
            int command = readInt(input);
            int arg0 = readInt(input);
            int arg1 = readInt(input);
            int length = readInt(input);
            int expectedChecksum = readInt(input);
            int magic = readInt(input);
            if (length < 0 || length > MAX_PACKET || magic != (command ^ 0xffffffff)) {
                throw new UnsupportedProtocol();
            }
            byte[] data = readExact(input, length);
            if (checksum(data) != expectedChecksum) throw new UnsupportedProtocol();
            return new Packet(command, arg0, arg1, data);
        }

        byte[] shell(String shellCommand, int maxBytes) throws Exception {
            final int localId = nextLocalId++;
            if (nextLocalId <= 0) nextLocalId = 1;
            write(A_OPEN, localId, 0, ("shell:" + shellCommand + "\0").getBytes(UTF8));
            int remoteId = 0;
            ByteArrayOutputStream collected = new ByteArrayOutputStream();
            while (true) {
                Packet packet = read();
                if ((packet.command == A_OKAY || packet.command == A_WRTE
                        || packet.command == A_CLSE) && packet.arg1 != localId) {
                    continue;
                }
                if (packet.command == A_OKAY) {
                    remoteId = packet.arg0;
                    continue;
                }
                if (packet.command == A_WRTE) {
                    remoteId = packet.arg0;
                    if (collected.size() + packet.data.length > maxBytes) {
                        throw new IOException("shell output too large");
                    }
                    collected.write(packet.data);
                    write(A_OKAY, localId, remoteId, new byte[0]);
                    continue;
                }
                if (packet.command == A_CLSE) {
                    if (remoteId == 0) remoteId = packet.arg0;
                    write(A_CLSE, localId, remoteId, new byte[0]);
                    return collected.toByteArray();
                }
                if (packet.command == A_SYNC) continue;
                throw new UnsupportedProtocol();
            }
        }

        void close() {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private static final class AuthorizationRequired extends IOException { }
    private static final class UnsupportedProtocol extends IOException { }

    private static final class ShellResult {
        final boolean success;
        final byte[] bytes;
        final String output;

        ShellResult(boolean success, byte[] bytes) {
            this.success = success;
            this.bytes = bytes;
            this.output = bytes.length <= 64 * 1024 ? new String(bytes, UTF8) : "";
        }
    }

    public static final class Result {
        public final boolean success;
        public final String status;
        public final String message;

        private Result(boolean success, String status, String message) {
            this.success = success;
            this.status = status;
            this.message = message;
        }

        private static Result success(String status, String message) {
            return new Result(true, status, message);
        }

        private static Result failure(String status, String message) {
            return new Result(false, status, message);
        }
    }

    public static final class InstallResult {
        public final boolean success;
        public final boolean definitive;
        public final String message;

        private InstallResult(boolean success, boolean definitive, String message) {
            this.success = success;
            this.definitive = definitive;
            this.message = message;
        }

        private static InstallResult success(String message) {
            return new InstallResult(true, true, message);
        }

        private static InstallResult failure(boolean definitive, String message) {
            return new InstallResult(false, definitive, message);
        }
    }
}
