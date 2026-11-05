package org.stackanvil.packetforge.commons;

import io.netty.buffer.ByteBuf;
import java.util.ArrayList;

public final class MovementCodecs {
    private MovementCodecs() {}

    public static MovementDelta read(ByteBuf input, int count) {
        if (count <= 0)
            return new MovementDelta.Linear(
                    input.readShort(), input.readShort(), input.readShort());
        if (count > input.readableBytes() / 7)
            throw new WireDecodeException("Movement step count exceeds available data");
        var steps = new ArrayList<MovementDelta.Step>(Math.min(count, 65536));
        for (int i = 0; i < count; i++) {
            int ticks = WireCodecs.VAR_INT.decode(input);
            steps.add(
                    new MovementDelta.Step(
                            input.readShort(), input.readShort(), input.readShort(), ticks));
        }
        return new MovementDelta.Stepped(steps);
    }

    public static void write(ByteBuf output, MovementDelta delta) {
        switch (delta) {
            case MovementDelta.Linear value -> {
                output.writeShort(value.x());
                output.writeShort(value.y());
                output.writeShort(value.z());
            }
            case MovementDelta.Stepped value -> {
                for (var step : value.steps()) {
                    WireCodecs.VAR_INT.encode(output, step.ticks());
                    output.writeShort(step.x());
                    output.writeShort(step.y());
                    output.writeShort(step.z());
                }
            }
        }
    }
}
