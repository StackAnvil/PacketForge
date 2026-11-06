package org.stackanvil.packetforge.protocol;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.key.Key;
import org.stackanvil.packetforge.commons.WireCodecs;

/** Compares Adventure identifiers with the isolated vanilla parser and wire codec. */
final class VanillaIdentifierAudit {
    private VanillaIdentifierAudit() {}

    static void run(ClassLoader loader, Class<?> byteBuf, Class<?> unpooled, Method decode, Method encode)
            throws Exception {
        Class<?> identifier = loader.loadClass("net.minecraft.resources.Identifier");
        Object codec = identifier.getField("STREAM_CODEC").get(null);
        var inputs = new ArrayList<>(List.of(
                "",
                ":",
                "stone",
                ":stone",
                "minecraft:",
                "test:a/b._-0",
                "...:..",
                "..:path",
                "test:a:b",
                "test:é",
                "é:path",
                "test:😀",
                "test:\ud800",
                "test:" + "a".repeat(32762),
                "test:" + "a".repeat(32763),
                "a".repeat(32768),
                "a".repeat(32767)));
        for (char character = 0; character < 128; character++) {
            inputs.add("test:" + character);
            inputs.add(character + ":path");
        }
        for (String value : inputs) {
            var input = Unpooled.buffer();
            var output = Unpooled.buffer();
            Object nativeInput = null;
            Object nativeOutput = null;
            try {
                WireCodecs.string(Math.max(32767, value.length())).encode(input, value);
                byte[] bytes = new byte[input.readableBytes()];
                input.getBytes(input.readerIndex(), bytes);
                nativeInput = unpooled.getMethod("wrappedBuffer", byte[].class).invoke(null, (Object) bytes);
                Object nativeValue;
                try {
                    nativeValue = decode.invoke(codec, nativeInput);
                } catch (InvocationTargetException invalid) {
                    assertTrue(isValidationFailure(invalid), invalid.toString());
                    assertThrows(IllegalArgumentException.class, () -> WireCodecs.IDENTIFIER.decode(input));
                    continue;
                }
                Key generated = WireCodecs.IDENTIFIER.decode(input);
                assertEquals(nativeValue.toString(), generated.asString());
                assertFalse(input.isReadable());
                assertEquals(0, byteBuf.getMethod("readableBytes").invoke(nativeInput));
                nativeOutput = unpooled.getMethod("buffer").invoke(null);
                try {
                    encode.invoke(codec, nativeOutput, nativeValue);
                } catch (InvocationTargetException invalid) {
                    assertTrue(isValidationFailure(invalid), invalid.toString());
                    assertThrows(IllegalArgumentException.class, () -> WireCodecs.IDENTIFIER.encode(output, generated));
                    continue;
                }
                WireCodecs.IDENTIFIER.encode(output, generated);
                assertBytesEqual(byteBuf, nativeOutput, output);
            } finally {
                input.release();
                output.release();
                if (nativeInput != null) byteBuf.getMethod("release").invoke(nativeInput);
                if (nativeOutput != null) byteBuf.getMethod("release").invoke(nativeOutput);
            }
        }
        // Direct construction can retain an empty namespace. Writing must not normalize it.
        Object nativeValue = identifier
                .getMethod("fromNamespaceAndPath", String.class, String.class)
                .invoke(null, "", "path");
        var output = Unpooled.buffer();
        Object nativeOutput = unpooled.getMethod("buffer").invoke(null);
        try {
            encode.invoke(codec, nativeOutput, nativeValue);
            WireCodecs.IDENTIFIER.encode(output, Key.key("", "path"));
            assertBytesEqual(byteBuf, nativeOutput, output);
        } finally {
            output.release();
            byteBuf.getMethod("release").invoke(nativeOutput);
        }
        assertEquals(273, inputs.size());
        System.out.println("Verified " + (inputs.size() + 1) + " identifier cases against vanilla");
    }

    private static boolean isValidationFailure(InvocationTargetException failure) {
        return switch (failure.getCause().getClass().getName()) {
            case "net.minecraft.IdentifierException",
                    "io.netty.handler.codec.DecoderException",
                    "io.netty.handler.codec.EncoderException" -> true;
            default -> false;
        };
    }

    private static void assertBytesEqual(Class<?> byteBuf, Object nativeOutput, io.netty.buffer.ByteBuf output)
            throws Exception {
        byte[] expected = new byte[(int) byteBuf.getMethod("readableBytes").invoke(nativeOutput)];
        byteBuf.getMethod("readBytes", byte[].class).invoke(nativeOutput, (Object) expected);
        byte[] actual = new byte[output.readableBytes()];
        output.readBytes(actual);
        assertArrayEquals(expected, actual);
    }
}
