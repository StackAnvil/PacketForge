package org.stackanvil.packetforge.commons;

public record ProfileProperty(String name, String value, String signature) {
    public static final PacketCodec<ProfileProperty> CODEC = WireCodecs.of(b -> new ProfileProperty(WireCodecs.string(64).decode(b), WireCodecs.STRING.decode(b), WireCodecs.nullable(WireCodecs.string(1024)).decode(b)),
        (b, property) -> { WireCodecs.string(64).encode(b, property.name()); WireCodecs.STRING.encode(b, property.value()); WireCodecs.nullable(WireCodecs.string(1024)).encode(b, property.signature()); });
}
