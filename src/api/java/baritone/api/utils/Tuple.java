package baritone.api.utils;

/**
 * Replacement for {@code net.minecraft.util.Tuple}, which was removed in Minecraft 26.2.
 *
 * <p>Only the surface Baritone actually uses is reproduced here: the two-arg constructor
 * and the {@code getA()} / {@code getB()} accessors. The vanilla class also had setters;
 * Baritone never called them, so this one is immutable.
 */
public class Tuple<A, B> {

    private final A a;
    private final B b;

    public Tuple(A a, B b) {
        this.a = a;
        this.b = b;
    }

    public A getA() {
        return this.a;
    }

    public B getB() {
        return this.b;
    }
}
