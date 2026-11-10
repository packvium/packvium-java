import com.packvium.commerce.CommerceInputException;
import com.packvium.serialization.JsonReader;
import com.packvium.serialization.JsonWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.packvium.commerce.Commerce.canonicalJson;
import static com.packvium.commerce.Commerce.catalogVersionInfo;
import static com.packvium.commerce.Commerce.evaluatePolicy;
import static com.packvium.commerce.Commerce.quote;

/**
 * Quote a shipment, apply a policy rule, and inspect a catalog version.
 *
 * <p>Run it:
 *
 * <pre>
 *     java -cp target/packvium-1.0.0.jar examples/Commerce.java
 * </pre>
 *
 * <p>Everything the three functions need arrives in one <em>commerce document</em>: the tariffs
 * you publish, the eligibility rules you publish, and the catalog versions you publish. Each
 * history is a list, and a version's number is simply its position in that list starting at 1
 * -- so {@code "tariff_version": 2} always means "the second entry under this carrier and
 * service", with no separate numbering to keep in sync.
 *
 * <p>Nothing here reads the clock. Every "which version applies" question is answered from a
 * pin you supply, so the same call replays to the same answer next year.
 */
public final class Commerce {
    // One document, three histories. You would normally load this from your own storage.
    //
    // The tariff has two published versions; the second takes effect at instant 1000. Volume
    // in mm^3 divided by `dimensional_weight_divisor` gives dimensional weight in grams.
    // `cost_per_dimensional_kg_minor` is minor currency units (cents) per billed kilogram, per
    // zone. `fuel_surcharge_permille` is permille: 120 means 12.0%.
    //
    // In the catalog, a rollback is a new, higher-numbered version, never an edit of history.
    private static final String DOCUMENT = """
            {
              "tariffs": [{
                "carrier_id": "acme",
                "service_id": "ground",
                "versions": [
                  {
                    "effective_at": 0,
                    "dimensional_weight_divisor": 5000,
                    "cost_per_dimensional_kg_minor": {"zone-a": 450, "zone-b": 610},
                    "minimum_charge_minor": 900,
                    "fuel_surcharge_permille": 120,
                    "accessorials": [
                      {"accessorial_id": "liftgate", "flat_charge_minor": 250},
                      {"accessorial_id": "residential", "permille_of_base": 75}
                    ]
                  },
                  {
                    "effective_at": 1000,
                    "dimensional_weight_divisor": 4000,
                    "cost_per_dimensional_kg_minor": {"zone-a": 480},
                    "minimum_charge_minor": 950,
                    "fuel_surcharge_permille": 140,
                    "accessorials": [{"accessorial_id": "liftgate", "flat_charge_minor": 275}]
                  }
                ]
              }],
              "policy_rules": [{
                "rule_id": "no-hazmat-air",
                "versions": [{
                  "scope": "hazmat",
                  "action": "reject",
                  "priority": 10,
                  "effective_at": 0,
                  "reason": "class 1.4 is not accepted on air services",
                  "predicates": [{
                    "scope": "hazmat", "field": "un_class",
                    "operator": "equals", "value": "1.4"
                  }]
                }]
              }],
              "catalogs": [{
                "catalog_id": "dc-12",
                "versions": [
                  {
                    "effective_at": 0, "published_at": 0, "note": "initial",
                    "snapshot": {
                      "items": [{"id": "sku-1", "dimensions_mm": [100, 200, 300], "weight_g": 1200}],
                      "cartons": [{"id": "box-m", "inner_dimensions_mm": [320, 240, 180],
                                   "max_payload_g": 15000, "cost_minor": 85}]
                    }
                  },
                  {"rollback_to": 1, "published_at": 900, "effective_at": 900,
                   "note": "revert the weight correction"}
                ]
              }]
            }
            """;

    public static void main(String[] arguments) {
        Map<String, Object> document = jsonObject(DOCUMENT);

        // 1. Quote: what does this shipment cost?
        Map<String, Object> pinned = quote(document, Map.of(
                "carrier_id", "acme",
                "service_id", "ground",
                "tariff_version", 1L, // replay against exactly this version...
                "zone", "zone-a",
                "actual_weight_g", 1200L,
                "volume_mm3", 6000000L,
                "requested_accessorials", List.of("liftgate")));
        show("a quote pinned to tariff version 1", pinned);
        System.out.print("   -> the caller pays " + member(pinned, "quote", "total_minor") + " minor units\n");

        Map<String, Object> effective = quote(document, Map.of(
                "carrier_id", "acme",
                "service_id", "ground",
                "as_of", 1500L, // ...or against whatever was in force at this instant
                "zone", "zone-a",
                "actual_weight_g", 1200L,
                "volume_mm3", 6000000L,
                "requested_accessorials", List.of("liftgate")));
        System.out.print("\n   as of instant 1500 the tariff is version " + member(effective, "quote", "tariff_version")
                + ", and the price is " + member(effective, "quote", "total_minor") + "\n");

        // A request the model cannot answer is not an exception. It is a result with a status,
        // a code from a closed set, and the structured fields that say what was missing.
        show("a zone this tariff does not price", quote(document, Map.of(
                "carrier_id", "acme", "service_id", "ground", "tariff_version", 1L,
                "zone", "zone-nowhere", "actual_weight_g", 1200L, "volume_mm3", 6000000L)));

        // A *malformed* request is a different thing entirely: that is your bug, and it throws.
        try {
            quote(document, Map.of(
                    "carrier_id", "acme", "service_id", "ground", "tariff_version", 1L,
                    "zone", "zone-a", "actual_weight_g", -1L, "volume_mm3", 6000000L));
        } catch (CommerceInputException error) {
            System.out.print("\n   a negative weight is refused before anything is priced: " + error.getMessage() + "\n");
        }

        // 2. Policy: may this shipment go at all?
        show("a policy decision, with the rule that made it", evaluatePolicy(document,
                Map.of("scope", "hazmat", "context", Map.of("un_class", "1.4"), "as_of", 0L)));

        Map<String, Object> allowed = evaluatePolicy(document,
                Map.of("scope", "hazmat", "context", Map.of("un_class", "9"), "as_of", 0L));
        Object citation = member(allowed, "decision", "citation");
        System.out.print("\n   nothing matched, so the shipment is allowed with no citation: "
                + (citation == null ? "null" : JsonWriter.write(citation)) + "\n");

        // 3. Catalog: which master data was this decision made against?
        Map<String, Object> catalog = catalogVersionInfo(document, Map.of(
                "catalog_id", "dc-12",
                "version", 2L,
                "resolved_at", 1700L));
        show("catalog version metadata", catalog);
        System.out.print("\n   version " + member(catalog, "catalog", "version") + " is a rollback of version "
                + member(catalog, "catalog", "rolled_back_from") + "\n");

        // Storing or comparing a result: use the canonical form, never a general JSON encoder.
        System.out.print("\n== the canonical form is what you store, log and compare\n");
        System.out.print(canonicalJson(pinned) + "\n");
    }

    /** The document as the commerce functions take it: a JSON object keyed by member name. */
    private static Map<String, Object> jsonObject(String json) {
        Map<String, Object> members = new LinkedHashMap<>();
        ((Map<?, ?>) JsonReader.parse(json)).forEach((name, value) -> members.put(name.toString(), value));
        return members;
    }

    private static Object member(Map<String, Object> result, String section, String field) {
        return ((Map<?, ?>) result.get(section)).get(field);
    }

    private static void show(String title, Map<String, Object> result) {
        System.out.print("\n== " + title + "\n");
        System.out.print(prettyJson(result) + "\n");
    }

    /** Four-space indented JSON, the layout most JSON tools print by default. */
    private static String prettyJson(Object value) {
        StringBuilder out = new StringBuilder();
        appendPretty(out, value, "");
        return out.toString();
    }

    private static void appendPretty(StringBuilder out, Object value, String indent) {
        if (value instanceof Map<?, ?> object && !object.isEmpty()) {
            String inner = indent + "    ";
            out.append("{\n");
            String separator = "";
            for (Map.Entry<?, ?> member : object.entrySet()) {
                out.append(separator).append(inner).append(JsonWriter.write(member.getKey().toString())).append(": ");
                appendPretty(out, member.getValue(), inner);
                separator = ",\n";
            }
            out.append('\n').append(indent).append('}');
        } else if (value instanceof List<?> array && !array.isEmpty()) {
            String inner = indent + "    ";
            out.append("[\n");
            String separator = "";
            for (Object element : array) {
                out.append(separator).append(inner);
                appendPretty(out, element, inner);
                separator = ",\n";
            }
            out.append('\n').append(indent).append(']');
        } else {
            out.append(JsonWriter.write(value));
        }
    }
}
