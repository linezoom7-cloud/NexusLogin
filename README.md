# ✦ Nexus Login

> **The Next-Generation, Zero-Lag, Argon2id PIN Gate for Paper 1.21.x**  
> *Say goodbye to outdated, chat-leaking authentication plugins.*

Nexus Login is a modern, enterprise-grade authentication plugin designed specifically for **Paper & Purpur (1.21.x)** running on **Java 21**. It replaces legacy chat-based registration with a sleek, clickable **Neon PIN keypad**, zero-dependency procedural resource pack generation, and military-grade **Argon2id** password hashing.

---

### ⚡ Why Nexus Login over Legacy Auth Plugins?

| Feature | 🦖 Legacy Auth Plugins (AuthMe) | ⚡ Nexus Login |
| :--- | :--- | :--- |
| **Password Security** | Outdated SHA256 / BCrypt (GPU crackable) | **OWASP Standard Argon2id** (Memory-hard) |
| **Chat Safety** | Prone to chat leaks (forgetting `/` exposes password) | **Clickable GUI PIN Pad** (Zero chat leak) |
| **Concurrency / TPS** | Heavy synchronous / blocking queries | **Java 21 Virtual Threads (Zero TPS loss)** |
| **Resource Pack** | Requires ItemsAdder / Oraxen / external host | **Self-contained (Procedural Java 2D + Built-in HTTP)** |
| **Multi-Language** | Clunky manual editing | **Instant English (`en`) & Turkish (`tr`) switcher** |
| **Memory Footprint** | Bloated fat JAR (~10 MB) | **Ultra-lightweight (~190 KB JAR)** |

---

## 🚀 Features

- **Clickable PIN Interface:** Supports 8–9 digit PINs with sequence and pattern analysis (blocks `12345678`, `11111111`, common PINs). Logins with a PIN created before the limit was raised still work.
- **Self-Hosted Resource Pack:** Procedurally draws a neon portal & chest texture in memory using `java.awt.Graphics2D` and serves it **over the Minecraft port itself** — no extra port, firewall rule, CDN, ItemsAdder or Oraxen required. Players who decline the pack automatically get the stained-glass fallback GUI.
- **Visible Diagnostics:** Every resource-pack status (accepted / downloaded / declined / failed) is logged, so "the pack doesn't work" is no longer silent.
- **Persistent Brute-Force Shield:** Failed attempts are counted **per account** (and optionally per IP) in the database, so reconnecting or restarting the server does not reset the counter. Each new lockout doubles the previous duration up to 24 hours.
- **Tamper-Evident Audit Log:** Every failed/successful login, PIN change, lockout and admin action is written to `plugins/NexusLogin/nexus-auth.log`; lockouts also raise a console `WARNING`.
- **Offline-mode Impersonation Protection:** Prevents cracked launchers from stealing names like "Notch" or taking over OP accounts.
- **Visual Feedback:** 48-frame pre-computed gradient BossBar animation, rotating portal dust particles, sounds, and blindfold isolation.
- **Safety First:** Temporary invulnerability, fire extinguishing, and inventory hiding during login, with 100% safe state restoration on auth or disconnect.

---

## 🛠️ Building & Installation

### Requirements
- **Server:** Paper or Purpur **1.21.1+** (1.21 – 1.21.4 supported)
- **Java:** **Java 21 LTS** or newer

### Building from Source
```powershell
.\gradlew.bat build
```
Output: `build/libs/NexusLogin-1.0.2.jar`

### Installation
1. Drop `NexusLogin-1.0.2.jar` into your server's `plugins/` folder.
2. Start the server! Paper will automatically download the required libraries (`HikariCP`, `Argon2-jvm`, `MariaDB Connector/J`) on first boot.
3. Nothing else — the resource pack is served over the Minecraft port (`resource-pack.delivery: same-port`). On shared hosting (Pterodactyl / Aternos-like panels) **do not** open port `8164`; it is not needed.
4. The generated ZIP is also written to `plugins/NexusLogin/pack/nexus-pack.zip`, so you can upload it to any static host if needed.

> Behind a Velocity/Bungee proxy the same-port trick cannot work (the proxy answers the port, not the backend). Set `resource-pack.delivery: port` and expose `resource-pack.port`, or host the generated ZIP yourself and use `delivery: url`.

> **MCServerHost / relay-based panels:** MCSH puts a Minecraft-aware relay in front of the server (the public `25565` reads the handshake before forwarding), so HTTP requests to that port are dropped even though the plugin's local self-test passes. Use the panel's *Resource Packs* section (or any static host) to upload `plugins/NexusLogin/pack/nexus-pack.zip`, then set:
> ```yaml
> resource-pack:
>   delivery: url
>   public-url: "https://.../nexus-pack.zip"
> ```

---

## 🌐 Configuration (`config.yml`)

```yaml
# Language: "en" (English) or "tr" (Turkish)
language: en

database:
  type: SQLITE # SQLITE or MARIADB
  mariadb:
    # enable TLS for remote databases: disable | trust | verify-ca | verify-full
    ssl-mode: disable # use verify-full when the DB is not on localhost

auth:
  timeout-seconds: 120
  max-accounts-per-ip: 3
  # WARNING: IP + UUID based; can bypass the PIN behind relays/shared IPs or in offline-mode.
  session-remember-minutes: 0 # 0 = off (recommended)
  # WARNING: removes the second factor; a stolen Minecraft session logs in without a PIN.
  premium-auto-login: false
  teleport-to-spawn: false
  restore-previous-location: true
  hide-inventory: true
  pin:
    min-length: 8
    max-length: 9
  # Persistent brute-force protection (stored in the database).
  throttle:
    window-minutes: 15
    account-threshold: 5    # always enforced; >= 1
    ip-threshold: 0         # 0 = OFF (see relay warning below)
    base-lockout-minutes: 1 # doubles on every new lockout
    max-lockout-minutes: 1440

resource-pack:
  enabled: true
  # same-port: served over the Minecraft port (no extra port needed)
  # port:      standalone HTTP server on `port`
  # url:       external address in `public-url`
  delivery: same-port
  port: 8164
  public-host: ""
  public-url: ""
  required: false

security:
  # The audit log rotates at this size and keeps this many backups.
  audit:
    max-size-kb: 1024 # 0 = rotation disabled (not recommended)
    keep-files: 5
  argon2:
    memory-kib: 65536
    iterations: 3
    parallelism: 1
    # Server-side secret mixed in with HMAC-SHA256 before Argon2 (>= 16 chars, "" = off).
    # WARNING: never change this after accounts exist — every PIN becomes invalid.
    pepper: ""
```

---

## 🛡️ Security & Brute-Force Protection

### Network trust model (read this first)

The PIN gate can only be as strong as the identity data it receives:

- **Backend servers must not be reachable directly.** With BungeeCord *legacy* forwarding the client supplies its own IP in the handshake, so `ip-threshold`, `max-accounts-per-ip` and `session-remember-minutes` can all be spoofed. Use **Velocity modern forwarding** (or PROXY protocol) and firewall the backend so only the proxy can connect.
- **`session-remember-minutes` defaults to `0` (off).** Remember-me is keyed by IP + UUID: behind a relay, MCSH, CGNAT or shared NAT every player looks like the same IP, and in offline-mode the UUID is derived from the name. Turning it on in those setups lets someone re-connect as another player without a PIN. Changing the PIN invalidates the remembered session.
- **`premium-auto-login` defaults to `false`.** It authenticates a player solely from the Minecraft session; a stolen Microsoft/Mojang session or a cracked client that can spoof the handshake has no second factor.
- **Optional pepper.** Set `security.argon2.pepper` to a random secret of at least 16 characters. Without it, a leaked database can be attacked offline; with it, the attacker also needs the server configuration. Changing the pepper invalidates all existing PINs, so set it once and keep it in a secret manager/backup.
- **MariaDB TLS.** Set `database.mariadb.ssl-mode: verify-full` for any database that is not on `localhost`; otherwise credentials and PIN hashes travel in clear text.

### Schema upgrade (v1 → v2)

Starting with 1.0.1 the plugin enforces **case-insensitive unique player names**. On first start it migrates automatically: if several rows share the same name, the oldest row is kept and the duplicates are deleted (a warning is logged with the count). Back up the database before upgrading a heavily modified server. The plugin refuses to start on a schema newer than itself, so a downgrade requires restoring the backup.

### Persistent, escalating lockout

Failed PIN attempts are stored in the `nexus_login_attempts` table, not in memory:

- **Account counter (always on):** keyed by UUID. A player who enters 4 wrong PINs cannot escape the counter by reconnecting or by waiting for a server restart.
- **IP counter (optional):** keyed by the connecting address. **Leave `ip-threshold: 0` on relay-based hosts** (MCServerHost, TCPShield-like relays, shared proxies) — those show every player with the relay's IP, so enabling it would lock innocent players out and let a single attacker lock the whole server. Set `20–30` only if real client IPs are forwarded.
- **Escalation:** the first lockout lasts `base-lockout-minutes`; each new lockout doubles it, capped at `max-lockout-minutes` (default 24 h). A completely quiet window lowers the level by one step so an old incident does not punish a player forever.
- **Reset rule:** a successful login clears only the **account** counter. The IP counter is intentionally kept, otherwise an attacker could burn the IP budget and then clear it by logging into their own account.
- **Admin unlock:** `/nexus unlock <player>` clears the account counter and the account's known registration/last IP counters in both memory and the database, without deleting the account. If IP throttling is enabled and the player's current address differs from the stored ones, `/nexus unlockip <ip>` clears that IP counter too. `/nexus unregister` and `/nexus resetpass` also clear those counters when they delete the account. No manual database edit is needed to release an accidentally locked admin.

Because the check runs in `AsyncPlayerPreLoginEvent`, a locked account never reaches the world — it is rejected at the network layer.

### Audit log

`plugins/NexusLogin/nexus-auth.log` receives one line per event:

```
LOGIN_FAIL  name=…  uuid=…  ip=…  attempts=4/5
LOCKOUT     name=…  uuid=…  ip=…  minutes=8  level=4
LOGIN_OK    name=…  uuid=…  ip=…  kind=PIN
PIN_CHANGE  name=…  uuid=…  ip=…
PRELOGIN_DENY name=…  uuid=…  ip=…  reason=locked
ADMIN       actor=…  action=unregister  target=…
ADMIN       actor=…  action=unlock      target=…
```

Lockouts are also printed to the console as `WARNING`, so an attack is visible without reading the file. User-supplied values are sanitised to prevent log-line injection.

The log rotates at `security.audit.max-size-kb` (default 1 MiB) into `nexus-auth.log.1`, `.2` … and keeps at most `security.audit.keep-files` backups (default 5). A sustained attack therefore cannot fill the disk. Set `max-size-kb: 0` only if you rotate the file yourself.

> **Note on relay hosts:** behind MCSH the `ip=` field is the relay's address, so IP-based blocking cannot distinguish players. Account counters remain effective; IP counters should stay disabled.

### Upgrading from the old counter

If your `config.yml` still has `max-attempts` / `lockout-minutes` and **no** `throttle` section, those legacy values are reused so protection does not silently disappear. Add the `throttle` section to get the new behaviour, and set `pin.min-length: 8` — existing shorter PINs still work for login.

### Schema upgrades

The database schema version is stored in a small `nexus_meta` table. Missing tables are created automatically on startup (`CREATE TABLE IF NOT EXISTS`), so upgrading from an older build needs no manual SQL. If the database was written by a **newer** plugin version, startup aborts with a clear error instead of risking data corruption; update the plugin rather than downgrading it.

---

## 🎮 Commands & Permissions

| Command | Aliases | Description | Permission |
| :--- | :--- | :--- | :--- |
| `/giris` | `/login`, `/l` | Re-opens the PIN gate if closed | *Default* |
| `/pin` | `/changepin`, `/pindegistir` | Change PIN code (logged-in players) | *Default* |
| `/nexus info <player>` | - | View player registration details | `nexuslogin.admin` |
| `/nexus unregister <player>` | - | Delete account & reset PIN (also clears lockout) | `nexuslogin.admin` |
| `/nexus resetpass <player>` | - | Alias of `unregister` (reset registration + lockout) | `nexuslogin.admin` |
| `/nexus unlock <player>` | - | Clear a brute-force lockout without deleting the account | `nexuslogin.admin` |
| `/nexus unlockip <ip>` | - | Clear a brute-force penalty for a specific IP address | `nexuslogin.admin` |
| `/nexus forcelogin <player>` | - | Bypass PIN and authenticate player | `nexuslogin.admin` |
| `/nexus reload` | - | Reload configuration & language files | `nexuslogin.admin` |

### What `/nexus reload` actually reloads

Applied live: authentication rules (`auth.*`), PIN length limits for new PINs, brute-force thresholds, the pre-login command whitelist, and the active language/messages.

Requires a full server restart: database settings, Argon2 cost parameters, resource-pack delivery settings, and audit rotation settings. The command tells you when a restart is needed instead of pretending everything was applied.

> Existing PINs always remain usable for login, even if you later lower `pin.max-length`. The smaller limit applies only when creating or changing a PIN.


---

<details>
<summary><b>🇹🇷 Türkçe Açıklama ve Özellikler (Genişletmek için tıklayın)</b></summary>

### Nexus Login — Yeni Nesil Paper 1.21 PIN Giriş Sistemi

Eski, hantal ve oyuncuların `/` koymayı unutup şifrelerini tüm sunucuya ifşa ettiği AuthMe devrini kapatan modern giriş sistemi.

- **Argon2id Güvenliği:** OWASP standartlarında bellek zorlamalı şifreleme; sızsa bile kırılamaz.
- **Kalıcı Kaba Kuvvet Koruması:** Hatalı denemeler hesap bazında veritabanında tutulur; yeniden bağlanmak veya sunucuyu yeniden başlatmak sayacı sıfırlamaz. Her yeni kilit süresi ikiye katlanır (en fazla 24 saat). Yanlışlıkla kilitlenen bir hesap, `/nexus unlock <oyuncu>` ile hesap silinmeden açılabilir.
- **Denetim Günlüğü:** Tüm giriş/kilit/PIN değişimi ve yönetici işlemleri `plugins/NexusLogin/nexus-auth.log` dosyasına yazılır; kilitler konsola `WARNING` olarak da düşer. Günlük 1 MiB'da döndürülür ve en fazla 5 yedek tutulur; bir saldırı diski dolduramaz.
- **Sıfır Bağımlılık:** ItemsAdder veya Oraxen gerekmez; doku paketini bellekte kendisi çizer ve **Minecraft'ın kendi portu üzerinden** dağıtır — ek port açtırmak gerekmez.
- **Java 21 Sanal Thread'leri:** Sıfır TPS kaybı, sunucuyu yormayan asenkron veritabanı.
- **Türkçe Desteği:** `config.yml` dosyasında `language: tr` yaparak tüm mesajları anında Türkçeye çevirebilirsiniz (`messages_tr.yml`).

</details>

---

## 📄 License
This project is licensed under the [MIT License](LICENSE).
