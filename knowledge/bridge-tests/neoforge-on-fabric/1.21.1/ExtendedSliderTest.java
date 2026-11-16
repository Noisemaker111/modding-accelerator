package ${package};

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

/** NeoForge's slider snaps to its step, labels itself prefix + value + suffix, and formats to the step's decimals. */
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
    }
}
