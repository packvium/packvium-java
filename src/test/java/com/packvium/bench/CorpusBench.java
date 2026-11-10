package com.packvium.bench;

import com.packvium.serialization.JsonReader;
import com.packvium.serialization.RequestPacker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Warm in-process timing of every fixture in a tag's corpus, printed as TSV
 * (fixture, median microseconds, time_limit_reached). Not a test: run it with
 * {@code java -cp target/classes:target/test-classes com.packvium.bench.CorpusBench <fixtures dir> <reps>}.
 */
public final class CorpusBench {
    private static final int WARMUP = 3;

    private CorpusBench() {
    }

    public static void main(String[] arguments) throws IOException {
        Path directory = Path.of(arguments[0]);
        int repetitions = Integer.parseInt(arguments[1]);
        List<Path> fixtures;
        try (Stream<Path> listing = Files.list(directory)) {
            fixtures = listing.filter(path -> path.toString().endsWith(".json")).sorted().toList();
        }
        for (Path fixture : fixtures) {
            Object request = JsonReader.parse(Files.readString(fixture));
            Boolean clockBound = null;
            try {
                for (int index = 0; index < WARMUP; index++) {
                    RequestPacker.pack(request);
                }
                long[] samples = new long[repetitions];
                for (int index = 0; index < repetitions; index++) {
                    long started = System.nanoTime();
                    Map<String, Object> result = RequestPacker.pack(request);
                    samples[index] = (System.nanoTime() - started) / 1_000;
                    clockBound = (Boolean) ((Map<?, ?>) result.get("algorithm")).get("time_limit_reached");
                }
                Arrays.sort(samples);
                System.out.println(fixture.getFileName() + "\t" + samples[repetitions / 2] + "\t" + clockBound);
            } catch (RuntimeException refusal) {
                System.out.println(fixture.getFileName() + "\trefused\t-");
            }
        }
    }
}
