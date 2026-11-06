package org.stackanvil.packetforge.commons;

/** A payload whose concrete type fixes its wire discriminator. Generated implementations are sealed. */
public interface TypedPayload<K> {
    K type();
}
