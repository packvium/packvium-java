package com.packvium.commerce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.packvium.serialization.JsonReader;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Canonical outputs and messages were taken from the PHP port at tag v1.0.0. */
class ApiTest {
    private static final String TARIFFS = "{\"tariffs\":[{\"carrier_id\":\"acme\",\"service_id\":\"ground\","
            + "\"versions\":[{\"effective_at\":0,\"dimensional_weight_divisor\":5000,"
            + "\"cost_per_dimensional_kg_minor\":{\"zone-a\":450}}]}]}";

    @SuppressWarnings("unchecked") // JsonReader yields an object for a JSON object literal
    private static Map<String, Object> json(String text) {
        return (Map<String, Object>) JsonReader.parse(text);
    }

    @Test
    void quotesTheDocumentedExample() {
        Map<String, Object> result = Commerce.quote(json(TARIFFS), json("{\"carrier_id\":\"acme\","
                + "\"service_id\":\"ground\",\"tariff_version\":1,\"zone\":\"zone-a\",\"actual_weight_g\":2000,"
                + "\"volume_mm3\":1000000}"));
        assertEquals("{\"api_version\":1,\"quote\":{\"accessorial_charges_minor\":[],\"actual_weight_g\":2000,"
                + "\"base_charge_minor\":900,\"billed_weight_g\":2000,\"carrier_id\":\"acme\","
                + "\"dimensional_weight_g\":200,\"fuel_surcharge_minor\":0,\"minimum_charge_applied\":false,"
                + "\"service_id\":\"ground\",\"tariff_version\":1,\"total_minor\":900,\"zone\":\"zone-a\"},"
                + "\"status\":\"ok\"}", Commerce.canonicalJson(result));
    }

    @Test
    void rejectsAnUnpriceableZoneWithStructuredFields() {
        Map<String, Object> result = Commerce.quote(json(TARIFFS), json("{\"carrier_id\":\"acme\","
                + "\"service_id\":\"ground\",\"as_of\":5,\"zone\":\"a\\u2028b\\b\",\"actual_weight_g\":1,"
                + "\"volume_mm3\":1}"));
        assertEquals("{\"api_version\":1,\"error\":{\"code\":\"unavailable_zone\",\"fields\":{\"carrier_id\":\"acme\","
                + "\"service_id\":\"ground\",\"tariff_version\":1,\"zone\":\"a\\u2028b\\b\"}},\"status\":\"rejected\"}",
                Commerce.canonicalJson(result));
    }

    @Test
    void reportsUnknownKeysInCodePointOrder() {
        CommerceInputException error = assertThrows(CommerceInputException.class,
                () -> Commerce.quote(json(TARIFFS), json("{\"carrier_id\":\"acme\",\"service_id\":\"ground\","
                        + "\"tariff_version\":1,\"zone\":\"z\",\"actual_weight_g\":1,\"volume_mm3\":1,"
                        + "\"\\ud83d\\ude00\":1,\"z\":2,\"A\":3}")));
        assertEquals("request: unrecognised key(s) ['A', 'z', '😀']", error.getMessage());
    }

    @Test
    void refusesABooleanWhereAnIntegerBelongs() {
        CommerceInputException error = assertThrows(CommerceInputException.class,
                () -> Commerce.quote(json(TARIFFS), json("{\"carrier_id\":\"acme\",\"service_id\":\"ground\","
                        + "\"tariff_version\":1,\"zone\":\"z\",\"actual_weight_g\":true,\"volume_mm3\":1}")));
        assertEquals("request.actual_weight_g: expected an exact integer", error.getMessage());
    }

    @Test
    void refusesAnIndexKeyedObjectWhereAnObjectBelongs() {
        CommerceInputException error = assertThrows(CommerceInputException.class,
                () -> Commerce.evaluatePolicy(json("{}"), json("{\"scope\":\"customer\",\"as_of\":0,"
                        + "\"context\":{\"0\":1}}")));
        assertEquals("request.context: expected an object", error.getMessage());
    }

    @Test
    void acceptsAnEmptyObjectWhereAListBelongs() {
        Map<String, Object> result = Commerce.evaluatePolicy(json("{\"policy_rules\":{}}"),
                json("{\"scope\":\"customer\",\"as_of\":0,\"context\":[]}"));
        assertEquals("{\"api_version\":1,\"decision\":{\"allowed\":true,\"citation\":null,\"scope\":\"customer\"},"
                + "\"status\":\"ok\"}", Commerce.canonicalJson(result));
    }

    @Test
    void rejectsAnUnknownCatalog() {
        Map<String, Object> result = Commerce.catalogVersionInfo(json("{}"),
                json("{\"catalog_id\":\"c\",\"resolved_at\":0,\"version\":2}"));
        assertEquals("{\"api_version\":1,\"error\":{\"code\":\"catalog_not_found\",\"fields\":{\"catalog_id\":\"c\"}},"
                + "\"status\":\"rejected\"}", Commerce.canonicalJson(result));
    }

    @Test
    void encodesAnEmptyObjectAsPhpDoes() {
        assertEquals("{\"a\":[],\"b\":[1]}", Commerce.canonicalJson(Map.of("b", List.of(1L), "a", Map.of())));
    }
}
