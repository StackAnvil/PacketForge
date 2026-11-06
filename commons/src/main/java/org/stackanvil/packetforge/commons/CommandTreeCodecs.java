package org.stackanvil.packetforge.commons;

import java.util.*;
import net.kyori.adventure.key.Key;

/** Command nodes with registered argument payloads and independent dependency graphs. */
public final class CommandTreeCodecs {
    private CommandTreeCodecs() {}

    public static <A extends TypedPayload<Integer>> PacketCodec<CommandNode<A>> node(
            Map<Integer, PacketCodec<A>> schemas) {
        var codecs = Map.copyOf(schemas);
        return WireCodecs.of(
                input -> {
                    int flags = input.readByte();
                    int[] children = WireCodecs.VAR_INT_ARRAY.decode(input);
                    int redirect = (flags & 8) != 0 ? WireCodecs.VAR_INT.decode(input) : 0;
                    CommandNode.Stub<A> stub = null;
                    if ((flags & 3) == 2) {
                        String name = WireCodecs.STRING.decode(input);
                        int id = WireCodecs.VAR_INT.decode(input);
                        var codec = codecs.get(id);
                        if (codec != null) {
                            A value = codec.decode(input);
                            if (value.type() != id)
                                throw new IllegalArgumentException(
                                        "Command argument discriminator does not match its codec");
                            Key suggestion = (flags & 16) != 0 ? WireCodecs.IDENTIFIER.decode(input) : null;
                            stub = new CommandNode.Argument<>(name, value, suggestion);
                        }
                    } else if ((flags & 3) == 1) stub = new CommandNode.Literal<>(WireCodecs.STRING.decode(input));
                    return new CommandNode<>(flags, children, redirect, stub);
                },
                (output, node) -> {
                    output.writeByte(node.flags());
                    WireCodecs.VAR_INT_ARRAY.encode(output, node.children());
                    if ((node.flags() & 8) != 0) WireCodecs.VAR_INT.encode(output, node.redirect());
                    if (node.stub() instanceof CommandNode.Literal<A> literal)
                        WireCodecs.STRING.encode(output, literal.name());
                    else if (node.stub() instanceof CommandNode.Argument<A> argument) {
                        WireCodecs.STRING.encode(output, argument.name());
                        int id = argument.argument().type();
                        var codec = codecs.get(id);
                        if (codec == null) throw new IllegalArgumentException("Unknown command argument ID " + id);
                        WireCodecs.VAR_INT.encode(output, id);
                        codec.encode(output, argument.argument());
                        if (argument.suggestion() != null) WireCodecs.IDENTIFIER.encode(output, argument.suggestion());
                    }
                });
    }

    public static void validate(List<? extends CommandNode<?>> nodes) {
        validate(nodes, true);
        validate(nodes, false);
    }

    private static void validate(List<? extends CommandNode<?>> nodes, boolean redirects) {
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
