import com.packvium.Packer;
import com.packvium.config.PackingConfig;
import com.packvium.domain.Container;
import com.packvium.domain.Dimensions;
import com.packvium.domain.Item;
import com.packvium.result.PackingResult;
import com.packvium.serialization.JsonWriter;
import com.packvium.serialization.ResultEncoder;
import com.packvium.unit.Weight;
import java.util.List;
import java.util.TreeSet;

/**
 * The smallest useful call: some items, some boxes, one answer.
 *
 * <p>Run it:
 *
 * <pre>
 *     java -cp target/packvium-1.1.0.jar examples/Basic.java
 * </pre>
 *
 * <p>Three things are worth noticing in a handful of lines.
 *
 * <p>{@code Dimensions.mm} and {@code Dimensions.inches} are both exact -- "4 in" is not
 * converted to a rounded number of millimetres, it is stored as an exact tick count, so an
 * imperial spec sheet and a metric container agree without a tolerance to tune (see Units.java).
 *
 * <p>{@code keepUpright} is a rule, not a hint. The mug will never be laid on its side, and if
 * that makes it not fit you are told which item failed and why, rather than getting a
 * plausible-looking arrangement that spills coffee.
 *
 * <p>{@code costMinor} is what the box costs <em>you</em>, in minor currency units. The default
 * objective ignores it -- it opens as few containers as possible and packs them tightly.
 * Ranking by packaging cost, by carrier-billed weight or by actual money is one setting away;
 * that is what Objectives.java is for.
 */
public final class Basic {
    public static void main(String[] arguments) {
        PackingResult result = new Packer(PackingConfig.balanced()).pack(
                List.of(
                        Item.builder("book", Dimensions.mm("210", "140", "30"))
                                .weight(Weight.parse("450 g")).quantity(4).build(),
                        Item.builder("mug", Dimensions.inches("4", "4", "5"))
                                .weight(Weight.parse("12 oz")).quantity(2).keepUpright(true).build()),
                List.of(
                        Container.builder("box-m", Dimensions.mm("400", "300", "250"))
                                .maxPayload(Weight.parse("20 kg")).costMinor(180).build(),
                        Container.builder("box-l", Dimensions.mm("500", "400", "350"))
                                .maxPayload(Weight.parse("30 kg")).costMinor(250).build()));

        List<String> chosen = result.containers().stream().map(packed -> packed.container().id()).toList();

        System.out.printf("status      %s\n", result.status().wireName());
        System.out.printf("containers  %s\n", JsonWriter.write(chosen));
        System.out.printf("packed      %d of 6\n", result.packedItemCount());
        System.out.printf("unpacked    %d\n", result.unpacked().size());
        System.out.printf("score       %s  <- lexicographic, exact integers, lower is better\n",
                JsonWriter.write(result.score()));

        System.out.print("\nthe full result as a plain array is what Serialization.java explores:\n");
        TreeSet<String> keys = new TreeSet<>(new ResultEncoder("mm", "g").encode(result).keySet());
        System.out.print(JsonWriter.write(List.copyOf(keys)) + "\n");
    }
}
