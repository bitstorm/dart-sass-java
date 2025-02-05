package de.larsgrefer.sass.embedded.util;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import sass.embedded_protocol.EmbeddedSass.Value.HwbColor;
import sass.embedded_protocol.EmbeddedSass.Value.RgbColor;

import java.awt.*;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ColorUtilTest {

    @Test
    void toJavaColor_hwb_red() {
        HwbColor red = HwbColor.newBuilder()
                .setHue(0d)
                .setWhiteness(0d)
                .setBlackness(0d)
                .setAlpha(1d)
                .build();

        Color javaRed = ColorUtil.toJavaColor(red);
        assertThat(javaRed).isEqualTo(Color.RED);
    }

    @Test
    void toJavaColor_hwb_black() {
        HwbColor black = HwbColor.newBuilder()
                .setHue(.33d)
                .setWhiteness(0d)
                .setBlackness(100d)
                .setAlpha(1d)
                .build();

        Color javaRed = ColorUtil.toJavaColor(black);
        assertThat(javaRed).isEqualTo(Color.BLACK);
    }

    @Test
    void toJavaColor_hwb_grey() {
        HwbColor grey = HwbColor.newBuilder()
                .setHue(.33d)
                .setWhiteness(50d)
                .setBlackness(50d)
                .setAlpha(1d)
                .build();

        Color javaRed = ColorUtil.toJavaColor(grey);
        assertThat(javaRed).isEqualTo(Color.GRAY);
    }

    @TestFactory
    Stream<DynamicTest> circle() {

        return Arrays.stream(Color.class.getFields())
                .filter(field -> Modifier.isPublic(field.getModifiers()))
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .filter(field -> Modifier.isFinal(field.getModifiers()))
                .filter(field -> field.getType().equals(Color.class))
                .map(colField -> DynamicTest.dynamicTest(colField.getName(), () -> {
                    Color col = (Color) colField.get(null);
                    RgbColor rgbColor = ColorUtil.toRgbColor(col);
                    HwbColor hwbColor = ColorUtil.toHwbColor(rgbColor);

                    assertThat(ColorUtil.toJavaColor(rgbColor)).isEqualTo(col);
                    assertThat(ColorUtil.toJavaColor(hwbColor)).isEqualTo(col);

                    assertThat(ColorUtil.toRgbColor(hwbColor)).isEqualTo(rgbColor);
                }));
    }
}