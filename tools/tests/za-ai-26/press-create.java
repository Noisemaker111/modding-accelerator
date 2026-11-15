var screen = mc.gui.screen();
String found = "screen " + (screen == null ? "none" : screen.getClass().getName()) + ":";
for (var child : screen.children()) {
    if (child instanceof net.minecraft.client.gui.components.Button button) {
        found += " [" + button.getMessage().getString() + "]";
        // 257 is Enter; the button's action ignores which input pressed it.
        if (button.getMessage().getString().equals("Create New World")) { button.onPress(new net.minecraft.client.input.KeyEvent(257, 0, 0)); return "pressed Create New World"; }
    }
}
return found;
