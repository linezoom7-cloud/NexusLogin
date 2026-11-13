package dev.nexus.login.concurrent;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Eklentinin tüm thread'lerinin tek sahibi.
 *
 * <ul>
 *   <li>{@link #io()} — JDBC gibi bloklayan G/Ç işleri için Java 21 Virtual Threads.
 *       Her görev kendi sanal thread'ini alır; bekleme sırasında taşıyıcı (carrier) thread serbest kalır.</li>
 *   <li>{@link #crypto()} — Argon2 için SABİT boyutlu platform thread havuzu. Argon2 bir
 *       JNA/native çağrıdır: sanal thread'i taşıyıcıya "pin"ler ve hash başına onlarca MiB RAM yer.
 *       Sınırsız sanal thread'le çalıştırmak, giriş spam'inde OOM'a davetiye çıkarır.</li>
 *   <li>{@link #mainThread()} — Bukkit API'sine dokunulacak sonuçları ana thread'e geri taşır.</li>
 * </ul>
 */
public final class NexusExecutors implements AutoCloseable {

    private static final long SHUTDOWN_TIMEOUT_SECONDS = 10;

    private final Plugin plugin;
    private final Logger logger;
    private final ExecutorService io;
    private final ExecutorService crypto;
    private final Executor mainThread;

    public NexusExecutors(Plugin plugin, int cryptoThreads) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.io = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("nexus-io-", 0).factory());
        this.crypto = Executors.newFixedThreadPool(cryptoThreads,
                Thread.ofPlatform().name("nexus-argon2-", 0).daemon(true).factory());
        this.mainThread = this::runOnMainThread;
    }

    public Executor io() {
        return io;
    }

    public Executor crypto() {
        return crypto;
    }

    public Executor mainThread() {
        return mainThread;
    }

    private void runOnMainThread(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
        // Eklenti kapanıyorsa scheduler görev kabul etmez; sessizce düşürülür.
    }

    /**
     * Önce yeni görev kabulünü durdurur, sonra kuyruktaki işlerin (ör. son last_ip yazımları)
     * bitmesini bekler. DatabaseManager bundan SONRA kapatılmalıdır.
     */
    @Override
    public void close() {
        shutdown("io", io);
        shutdown("crypto", crypto);
    }

    private void shutdown(String name, ExecutorService service) {
        service.shutdown();
        try {
            if (!service.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                logger.warning(() -> "'" + name + "' havuzu " + SHUTDOWN_TIMEOUT_SECONDS
                        + " sn içinde bitmedi, zorla durduruluyor.");
                service.shutdownNow();
            }
        } catch (InterruptedException e) {
            service.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
