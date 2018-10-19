package me.kenzierocks.a2m;

import static org.junit.Assert.*;

import java.util.Random;
import java.util.stream.DoubleStream;

import org.junit.Test;

import me.kenzierocks.a2m.v2.Processor;

public class CorrectPhiTest {

    @Test
    public void testPhiMatch() throws Exception {
        DoubleStream cases = new Random().doubles(100_000_0)
                .map(d -> (Math.PI * 6 * d) - Math.PI * 3);
        cases.forEach(
                x -> assertEquals("Case: " + x + " (" + Double.doubleToLongBits(x) + ")",
                        Processor.correctPhiLoop(x), Processor.correctPhiMod(x), 0.000001));
    }
}
