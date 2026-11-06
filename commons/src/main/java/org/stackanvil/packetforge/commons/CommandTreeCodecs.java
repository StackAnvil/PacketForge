package org.stackanvil.packetforge.commons;

import java.util.*;
import net.kyori.adventure.key.Key;

/** Command nodes with registered argument payloads and independent dependency graphs. */
public final class CommandTreeCodecs {
    private CommandTreeCodecs() {}

    public static PacketCodec<CommandNode> node(Map<Integer, PacketCodec<?>> schemas) {
        var codecs = Map.copyOf(schemas);
        return WireCodecs.of(
                input -> {
                    int flags = input.readByte();
                    int[] children = WireCodecs.VAR_INT_ARRAY.decode(input);
                    int redirect = (flags & 8) != 0 ? WireCodecs.VAR_INT.decode(input) : 0;
                    CommandNode.Stub stub = null;
                    if ((flags & 3) == 2) {
                        String name = WireCodecs.STRING.decode(input);
                        int id = WireCodecs.VAR_INT.decode(input);
                        var codec = codecs.get(id);
                        if (codec != null) {
                            Object value = codec.decode(input);
                            Key suggestion = (flags & 16) != 0 ? WireCodecs.IDENTIFIER.decode(input) : null;
                            stub = new CommandNode.Argument(name, new Variant<>(id, value), suggestion);
                        }
                    } else if ((flags & 3) == 1) stub = new CommandNode.Literal(WireCodecs.STRING.decode(input));
                    return new CommandNode(flags, children, redirect, stub);
                },
                (output, node) -> {
                    output.writeByte(node.flags());
                    WireCodecs.VAR_INT_ARRAY.encode(output, node.children());
                    if ((node.flags() & 8) != 0) WireCodecs.VAR_INT.encode(output, node.redirect());
                    if (node.stub() instanceof CommandNode.Literal literal)
                        WireCodecs.STRING.encode(output, literal.name());
                    else if (node.stub() instanceof CommandNode.Argument argument) {
                        WireCodecs.STRING.encode(output, argument.name());
                        int id = argument.argument().type();
                        var codec = codecs.get(id);
                        if (codec == null) throw new IllegalArgumentException("Unknown command argument ID " + id);
                        WireCodecs.VAR_INT.encode(output, id);
                        write(codec, output, argument.argument().value());
                        if (argument.suggestion() != null) WireCodecs.IDENTIFIER.encode(output, argument.suggestion());
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private static void write(PacketCodec<?> codec, io.netty.buffer.ByteBuf output, Object value) {
        ((PacketCodec<Object>) codec).encode(output, value);
    }

    public static void validate(List<CommandNode> nodes) {
        validate(nodes, true);
        validate(nodes, false);
    }

    private static void validate(List<CommandNode> nodes, boolean redirects) {
        int size = nodes.size();
        int[] pending = new int[size];
        List<List<Integer>> dependents = new ArrayList<>(size);
        for (int i = 0; i < size; i++) dependents.add(new ArrayList<>());
        for (int i = 0; i < size; i++) {
            var node = nodes.get(i);
            int[] references =
                    redirects ? (node.flags() & 8) != 0 ? new int[] {node.redirect()} : new int[0] : node.children();
            for (int reference : references)
                if (reference >= 0 && reference < size) {
                    pending[i]++;
                    dependents.get(reference).add(i);
                }
        }
        ArrayDeque<Integer> ready = new ArrayDeque<>();
        for (int i = 0; i < size; i++) if (pending[i] == 0) ready.add(i);
        int resolved = 0;
        while (!ready.isEmpty()) {
            int index = ready.removeFirst();
            resolved++;
            for (int dependent : dependents.get(index)) if (--pending[dependent] == 0) ready.add(dependent);
        }
        if (resolved != size) throw new IllegalStateException("Server sent an impossible command tree");
    }
}
