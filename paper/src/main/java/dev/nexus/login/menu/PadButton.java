package dev.nexus.login.menu;

/** Tuş takımındaki tıklanabilir öğeler. Sealed: switch ifadeleri tüm durumları kapsamak zorunda. */
public sealed interface PadButton {

    record Digit(char value) implements PadButton {
    }

    record Delete() implements PadButton {
    }

    record Confirm() implements PadButton {
    }
}
