package dev.nexus.login.pack;

import org.bukkit.NamespacedKey;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Menüde kullanılan özel item modelleri.
 *
 * <p>1.21.4+ istemcilerde bir eşyanın görünümü {@code minecraft:item_model} bileşeniyle
 * doğrudan seçilir: {@code assets/nexus/items/<ad>.json} → {@code assets/nexus/models/item/<ad>.json}
 * → {@code assets/nexus/textures/item/<ad>.png}. Bu yüzden eski {@code custom_model_data}
 * numaralandırması yoktur; her görsel kendi adıyla eşleşir.
 *
 * <p>Daha eski istemcilerde (ViaVersion arkasında) bileşen yok sayılır ve eşyanın kendi
 * dokusu görünür; bu yüzden her modelin bir {@code Material} yedeği vardır.
 */
public final class PackModels {

    public static final String GATE = "icon_gate";
    public static final String KEY = "icon_key";
    public static final String DELETE = "key_delete";
    public static final String CONFIRM = "key_confirm";
    public static final String CONFIRM_LOCKED = "key_confirm_locked";
    public static final String DOT_FILLED = "pin_filled";
    public static final String DOT_EMPTY = "pin_empty";

    /** Pakete yazılacak tüm model adları; {@link PackBuilder} bu listeyi dolaşır. */
    public static final List<String> ALL = List.of(
            digit(0), digit(1), digit(2), digit(3), digit(4),
            digit(5), digit(6), digit(7), digit(8), digit(9),
            DELETE, CONFIRM, CONFIRM_LOCKED, DOT_FILLED, DOT_EMPTY, GATE, KEY);

    private PackModels() {
    }

    public static String digit(int digit) {
        return "key_" + digit;
    }

    /** {@code nexus:<ad>} anahtarını üretir; geçersizse {@code null}. */
    public static @Nullable NamespacedKey id(String name) {
        return NamespacedKey.fromString("nexus:" + name);
    }
}
