package ${package};

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

/** NeoForge's slider snaps to its step and range, labels itself prefix + value + suffix (or not at all), and formats to the step's decimals. */
class ExtendedSliderTest {

    @Test
    void valuesSnapToTheStep() {
        ExtendedSlider slider = new ExtendedSlider(0, 0, 100, 20, Component.literal("Speed: "), Component.literal(" t"), 0, 10, 5, 2, 0, true);
        assertEquals(6, slider.getValueInt());
        assertEquals("Speed: 6 t", slider.getMessage().getString());
        slider.setValue(3.1);
        assertEquals(4, slider.getValueInt());
        ExtendedSlider fine = new ExtendedSlider(0, 0, 100, 20, Component.empty(), Component.empty(), 0, 1, 0.26, 0.25, 0, true);
        assertEquals("0.25", fine.getValueString());
        ExtendedSlider reversed = new ExtendedSlider(0, 0, 100, 20, Component.empty(), Component.empty(), 0, 10, 5, 1, 0, false);
        assertEquals("", reversed.getMessage().getString());
        reversed.setValue(20);
        assertEquals(10, reversed.getValueInt());
    }
}
