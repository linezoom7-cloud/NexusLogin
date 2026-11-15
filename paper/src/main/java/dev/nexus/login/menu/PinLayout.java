package dev.nexus.login.menu;

import java.util.Map;
import java.util.Optional;

/**
 * 6 satırlık sandık menüsünün slot haritası. Hem menü (item yerleşimi) hem de kaynak paketi
 * arka planı (slot kutularının piksel konumu) bu TEK kaynaktan beslenir; ikisi asla kaymaz.
 *
 * <pre>
 *  satır 0 : [ PIN göstergesi: ● ● ● ○ ○ ○ ]
 *  satır 1 :            [ bilgi ]
 *  satır 2 :          [1] [2] [3]
 *  satır 3 :  portal  [4] [5] [6]   kilit
 *  satır 4 :          [7] [8] [9]
 *  satır 5 :          [⌫] [0] [✔]
 * </pre>
 */
public final class PinLayout {

    public static final int ROWS = 6;
    public static final int SIZE = ROWS * 9;

    public static final int INFO_SLOT = 13;
    public static final int DELETE_SLOT = 48;
    public static final int CONFIRM_SLOT = 50;

    /** İndeks = rakam. */
    public static final int[] DIGIT_SLOTS = {49, 21, 22, 23, 30, 31, 32, 39, 40, 41};

    private static final Map<Integer, PadButton> BUTTONS = buildButtons();

    private PinLayout() {
    }

    private static Map<Integer, PadButton> buildButtons() {
        var map = new java.util.HashMap<Integer, PadButton>();
        for (int digit = 0; digit < DIGIT_SLOTS.length; digit++) {
            map.put(DIGIT_SLOTS[digit], new PadButton.Digit((char) ('0' + digit)));
        }
        map.put(DELETE_SLOT, new PadButton.Delete());
        map.put(CONFIRM_SLOT, new PadButton.Confirm());
        return Map.copyOf(map);
    }

    public static Optional<PadButton> buttonAt(int slot) {
        return Optional.ofNullable(BUTTONS.get(slot));
    }

    /** Gösterge satırında PIN'in ortalanmış başlangıç slotu. */
    public static int displayStart(int maxLength) {
        return (9 - maxLength) / 2;
    }

    // ── Sandık GUI dokusundaki piksel koordinatları (176 px genişlik) ──

    public static int slotX(int slot) {
        return 7 + (slot % 9) * 18;
    }

    public static int slotY(int slot) {
        return 17 + (slot / 9) * 18;
    }
}
