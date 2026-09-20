// Synthetic standalone probe. Not an application component or an automatic test.
// Compile against the ACTUAL compiled engine, BC, Kotlin stdlib and Okio.
import eu.kanade.tachiyomi.data.backup.crypto.*;
import java.io.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Cipher;

public final class Benchmark {
    interface Operation { byte[] run(); }
    static final Runtime RT = Runtime.getRuntime();
    static long used() { return RT.totalMemory() - RT.freeMemory(); }
    static byte[] measure(String name, Operation operation) throws Exception {
        System.gc();
        Thread.sleep(100);
        final long baseline = used();
        final AtomicLong peak = new AtomicLong(baseline);
        Thread sampler = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                peak.accumulateAndGet(used(), Math::max);
                try { Thread.sleep(1); } catch (InterruptedException e) { return; }
            }
        });
        sampler.setDaemon(true);
        sampler.start();
        long start = System.nanoTime();
        byte[] result;
        try {
            result = operation.run();
        } finally {
            peak.accumulateAndGet(used(), Math::max);
            sampler.interrupt();
            sampler.join();
        }
        System.out.printf(Locale.ROOT,
            "%s ms=%.2f baselineMiB=%.2f sampledPeakMiB=%.2f additionalMiB=%.2f%n",
            name, (System.nanoTime()-start)/1e6, baseline/1048576.0,
            peak.get()/1048576.0, (peak.get()-baseline)/1048576.0);
        return result;
    }
    static void primitives(byte[] payload, char[] password, Argon2Profile profile) throws Exception {
        SecureRandom rng = new SecureRandom();
        byte[] salt = new byte[16], nonce = new byte[12];
        rng.nextBytes(salt); rng.nextBytes(nonce);
        byte[] aad = PindobkFormat.INSTANCE.encode(new PindobkHeader(profile, payload.length, salt, nonce));
        byte[] encoded = Argon2KeyDerivation.INSTANCE.encodePassword(password);
        byte[] key = measure("kdf", () -> Argon2KeyDerivation.INSTANCE.derive(encoded, salt, profile));
        byte[] ciphertext = measure("aesEncrypt", () -> BackupAesGcm.INSTANCE.encrypt(payload, key, nonce, aad));
        byte[] plaintext = measure("aesDecrypt", () -> BackupAesGcm.INSTANCE.decrypt(ciphertext, key, nonce, aad));
        if (!Arrays.equals(payload, plaintext)) throw new AssertionError("primitive mismatch");
        Arrays.fill(plaintext, (byte)0);
        Arrays.fill(key, (byte)0);
        Arrays.fill(encoded, (byte)0);
    }
    public static void main(String[] args) throws Exception {
        int size = Integer.parseInt(args[0]) * 1024 * 1024;
        System.out.println("payloadMiB=" + size/1048576 + " maxHeapMiB=" + RT.maxMemory()/1048576
            + " provider=" + Cipher.getInstance("AES/GCM/NoPadding").getProvider().getName());
        BackupCrypto engine = new BackupCrypto();
        char[] password = "Synthetic benchmark password 0.4.2".toCharArray();
        byte[] payload = new byte[size];
        for (int i=0; i<size; i++) payload[i] = (byte)(i*31);
        try {
            // Production admission check is applied even to the primitive-only measurements.
            Argon2Profile profile = Argon2Profile.Companion.getPRODUCTION();
            CryptoMemoryBudget.Companion.getRUNTIME().check(size, profile);
            primitives(payload, password, profile);
            byte[] file = measure("engineEncrypt", () -> engine.encrypt(payload, password));
            byte[] restored = measure("engineDecrypt", () -> engine.decrypt(file, password));
            if (!Arrays.equals(payload, restored)) throw new AssertionError("engine mismatch");
            Arrays.fill(restored, (byte)0);
            if (args.length > 1) {
                // Only public synthetic fixtures explicitly passed by the operator.
                byte[] fixture = Files.readAllBytes(Paths.get(args[1]));
                byte[] expected = Files.readAllBytes(Paths.get(args[2]));
                char[] fixturePassword = "Senha sintética 🔒\u0000 e\u0301".toCharArray();
                byte[] actual = engine.decrypt(fixture, fixturePassword);
                if (!Arrays.equals(expected, actual)) throw new AssertionError("reference fixture mismatch");
                fixture[fixture.length-1] ^= 1;
                try { engine.decrypt(fixture, fixturePassword); throw new AssertionError("invalid tag accepted"); }
                catch (Exception e) {
                    if (!(e instanceof BackupCryptoException) ||
                        ((BackupCryptoException)e).getError() != BackupCryptoError.AUTHENTICATION_FAILED) throw e;
                }
                System.out.println("referenceFixture=PASS invalidTag=REJECTED");
            }
            System.out.println("result=PASS");
        } catch (Exception e) {
            if (!(e instanceof BackupCryptoException)) throw e;
            System.out.println("result=" + ((BackupCryptoException)e).getError().name());
        } finally {
            Arrays.fill(password, '\0');
            Arrays.fill(payload, (byte)0);
        }
        // Own process only, no application/user data. VmHWM is lifetime RSS high-water mark.
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/status"))) {
            String line;
            while ((line=reader.readLine()) != null) {
                if (line.startsWith("VmHWM:") || line.startsWith("VmRSS:")) System.out.println(line);
            }
        }
    }
}
