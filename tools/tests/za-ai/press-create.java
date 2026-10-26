var screen = mc.screen;
String found = "screen " + (screen == null ? "none" : screen.getClass().getName()) + ":";
for (var child : screen.children()) {
    if (child instanceof net.minecraft.client.gui.components.Button button) {
        found += " [" + button.getMessage().getString() + "]";
        if (button.getMessage().getString().equals("Create New World")) { button.onPress(); return "pressed Create New World"; }
    }
}
return found;
