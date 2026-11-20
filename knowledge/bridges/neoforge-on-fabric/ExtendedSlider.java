package ${package};

import com.mojang.blaze3d.platform.InputConstants;
import java.text.DecimalFormat;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * NeoForge's net.neoforged.neoforge.client.gui.widget.ExtendedSlider: the game's slider over a range from minValue to
 * maxValue, snapped to stepSize (none when 0), labelled prefix + value + suffix when drawString is set, the value
 * formatted to the step's decimals (or to precision decimals, at most 4, without a step). The arrow keys move it one
 * step. setValue takes a value in the range (the game's own takes a fraction), so the game's mouse and key handling,
 * which call it, are replaced as NeoForge replaces them. It draws as the game's slider.
 */
public class ExtendedSlider extends AbstractSliderButton {

    protected Component prefix;
    protected Component suffix;
    protected double minValue;
    protected double maxValue;
    protected double stepSize;
    protected boolean drawString;
    private final DecimalFormat format;

    public ExtendedSlider(int x, int y, int width, int height, Component prefix, Component suffix, double minValue, double maxValue, double currentValue, double stepSize, int precision, boolean drawString) {
        super(x, y, width, height, Component.empty(), 0D);
        this.prefix = prefix;
        this.suffix = suffix;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.stepSize = Math.abs(stepSize);
        this.value = this.snapToNearest((currentValue - minValue) / (maxValue - minValue));
        this.drawString = drawString;
        if (stepSize == 0D) {
            int decimals = Math.min(precision, 4);
            StringBuilder pattern = new StringBuilder("0");
            if (decimals > 0) pattern.append('.');
            for (int i = 0; i < decimals; i++) pattern.append('0');
            this.format = new DecimalFormat(pattern.toString());
        } else if (Mth.equal(this.stepSize, Math.floor(this.stepSize))) {
            this.format = new DecimalFormat("0");
        } else {
            this.format = new DecimalFormat(Double.toString(this.stepSize).replaceAll("\\d", "0"));
        }
        this.updateMessage();
    }

    public ExtendedSlider(int x, int y, int width, int height, Component prefix, Component suffix, double minValue, double maxValue, double currentValue, boolean drawString) {
        this(x, y, width, height, prefix, suffix, minValue, maxValue, currentValue, 1D, 0, drawString);
    }

    public double getValue() {
        return this.value * (maxValue - minValue) + minValue;
    }

    public long getValueLong() {
        return Math.round(this.getValue());
    }

    public int getValueInt() {
        return (int) this.getValueLong();
    }

    @Override
    public void setValue(double value) {
        this.value = this.snapToNearest((value - this.minValue) / (this.maxValue - this.minValue));
        this.updateMessage();
    }

    public String getValueString() {
        return this.format.format(this.getValue());
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        this.setValueFromMouse(event.x());
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dragX, double dragY) {
        this.setValueFromMouse(event.x());
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        boolean left = event.key() == InputConstants.KEY_LEFT;
        if (left || event.key() == InputConstants.KEY_RIGHT) {
            if (this.minValue > this.maxValue) left = !left;
            float direction = left ? -1F : 1F;
            if (stepSize <= 0D) this.setFractionalValue(this.value + (direction / (this.width - 8)));
            else this.setValue(this.getValue() + direction * this.stepSize);
        }
        return false;
    }

    private void setValueFromMouse(double mouseX) {
        this.setFractionalValue((mouseX - (this.getX() + 4)) / (this.width - 8));
    }

    private void setFractionalValue(double value) {
        double oldValue = this.value;
        this.value = this.snapToNearest(value);
        if (!Mth.equal(oldValue, this.value)) this.applyValue();
        this.updateMessage();
    }

    private double snapToNearest(double value) {
        if (stepSize <= 0D) return Mth.clamp(value, 0D, 1D);
        double snapped = Mth.lerp(Mth.clamp(value, 0D, 1D), this.minValue, this.maxValue);
        snapped = stepSize * Math.round(snapped / stepSize);
        snapped = this.minValue > this.maxValue ? Mth.clamp(snapped, this.maxValue, this.minValue) : Mth.clamp(snapped, this.minValue, this.maxValue);
        return Mth.map(snapped, this.minValue, this.maxValue, 0D, 1D);
    }

    @Override
    protected void updateMessage() {
        if (this.drawString) this.setMessage(Component.literal("").append(prefix).append(this.getValueString()).append(suffix));
        else this.setMessage(Component.empty());
    }

    @Override
    protected void applyValue() {}
}
