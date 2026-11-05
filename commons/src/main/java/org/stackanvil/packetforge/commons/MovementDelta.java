package org.stackanvil.packetforge.commons;

import java.util.List;

/** Relative movement encoded as three shorts or a sequence of timed steps. */
public sealed interface MovementDelta permits MovementDelta.Linear, MovementDelta.Stepped {
    int stepCount();

    record Linear(short x, short y, short z) implements MovementDelta {
        public int stepCount() {
            return 0;
        }
    }

    record Step(short x, short y, short z, int ticks) {}

    record Stepped(List<Step> steps) implements MovementDelta {
        public Stepped {
            steps = List.copyOf(steps);
            if (steps.isEmpty()) throw new IllegalArgumentException("Stepped movement needs at least one step");
        }

        public int stepCount() {
            return steps.size();
        }
    }
}
