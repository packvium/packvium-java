package com.packvium.artifacts;

import com.packvium.serialization.JsonReader;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JSON, CSV and print-ready work orders from an operational artifact.
 *
 * <p>Byte-identical in every Packvium engine. Each export is a pure function of
 * the artifact: no solver, carrier, renderer or clock, so an export is replayable from the
 * artifact it came from and every engine emits the same bytes.
 *
 * <ul>
 *   <li>JSON is the artifact's RFC 8785 canonical form.
 *   <li>CSV has one row per packing step and one per unplaced item. It is RFC 4180: a header
 *       row, CRLF after every row, and a field quoted only when it holds a comma, quote, CR or LF.
 *   <li>The work order is one self-contained HTML document: inline print styles, no script, no
 *       external resource, entities for the few typographic characters.
 * </ul>
 *
 * <p>Values are copied, never re-rendered or reinterpreted. That includes a CSV field beginning
 * with {@code =}: prefixing it would change an identifier a warehouse system matches on. Only a
 * string, an integer or null is written as text; a boolean, a float, a list or an object has a
 * different default spelling in each language and is refused with {@code invalid_value}.
 *
 * <p>Every export takes the artifact {@link OperationalArtifact#build} returned, or one read back
 * with {@link OperationalArtifact#parse}, and reads it <em>through its canonical form</em>, as the
 * Python engine does: an export is a function of the bytes every engine agrees on, so a {@code 1.0}
 * a caller put in the tree is the {@code 1} every engine reads back. A member a v1 document
 * always has, missing or of the wrong JSON type, is {@code unknown_format}, as in PHP.
 * O(N) for an artifact of N values, plus the canonical form's key sorts.
 */
public final class ArtifactExports {
    public static final List<String> CSV_COLUMNS = List.of(
            "record", "container_index", "container_type", "sequence", "item_type", "orientation",
            "x_ticks", "y_ticks", "z_ticks", "x", "y", "z", "length", "width", "height", "length_unit",
            "reason", "proof_level");

    private static final String STYLE = "body{font-family:system-ui,sans-serif;margin:24px;color:#111}"
            + "h1{font-size:20px}h2{font-size:16px;margin-top:24px}"
            + "table{border-collapse:collapse;width:100%;margin:8px 0 16px}"
            + "th,td{border:1px solid #999;padding:4px 6px;text-align:left;font-size:12px;vertical-align:top}"
            + "th{background:#eee}"
            + ".facts td:first-child{width:28%;font-weight:600}"
            + "@media print{body{margin:0}section.container{break-after:page}tr{break-inside:avoid}}";

    private static final String DASH = "&mdash;";

    private ArtifactExports() {
    }

    /** @throws OperationalArtifactException {@code unknown_format}, or the canonical writer's code */
    public static String json(Object artifact) {
        return OperationalArtifact.canonicalJson(requireArtifact(artifact));
    }

    /** @throws OperationalArtifactException {@code unknown_format}, {@code invalid_value}, or the canonical writer's code */
    public static String csv(Object artifact) {
        Object document = JsonReader.parse(OperationalArtifact.canonicalJson(requireArtifact(artifact)));
        Object workOrder = field(document, "work_order");
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.copyOf(CSV_COLUMNS));
        for (Object container : items(field(workOrder, "containers"))) {
            for (Object line : items(field(container, "lines"))) {
                rows.add(stepRow(workOrder, container, line));
            }
        }
        for (Object unplaced : items(field(field(document, "plan"), "unplaced"))) {
            Object facts = field(unplaced, "facts");
            List<Object> row = new ArrayList<>(CSV_COLUMNS.size());
            row.add("unplaced");
            row.add(null);
            row.add(null);
            row.add(null);
            row.add(field(facts, "item_type"));
            for (int column = 0; column < 11; column++) {
                row.add(null);
            }
            row.add(field(facts, "reason"));
            row.add(field(facts, "proof_level"));
            rows.add(row);
        }
        StringBuilder text = new StringBuilder();
        for (List<Object> row : rows) {
            for (int index = 0; index < row.size(); index++) {
                if (index > 0) {
                    text.append(',');
                }
                text.append(csvField(row.get(index)));
            }
            text.append("\r\n");
        }
        return text.toString();
    }

    /** @throws OperationalArtifactException {@code unknown_format}, {@code invalid_value}, or the canonical writer's code */
    public static String workOrderHtml(Object artifact) {
        Object document = JsonReader.parse(OperationalArtifact.canonicalJson(requireArtifact(artifact)));
        Object plan = field(document, "plan");
        Object workOrder = field(document, "work_order");
        Object provenance = field(document, "provenance");
        List<String> lines = new ArrayList<>(List.of(
                "<!DOCTYPE html>",
                "<html lang=\"en\">",
                "<head>",
                "<meta charset=\"utf-8\">",
                "<title>Packing work order</title>",
                "<style>" + STYLE + "</style>",
                "</head>",
                "<body>",
                "<h1>Packing work order</h1>",
                "<table class=\"facts\">"));
        lines.addAll(factRows(document, plan, provenance));
        lines.add("</table>");
        List<?> planContainers = items(field(plan, "containers"));
        List<?> workOrderContainers = items(field(workOrder, "containers"));
        // As the reference's `zip`: one section per pair, as many as the shorter list holds.
        int sections = Math.min(planContainers.size(), workOrderContainers.size());
        for (int index = 0; index < sections; index++) {
            lines.addAll(containerSection(planContainers.get(index), workOrderContainers.get(index), workOrder));
        }
        lines.addAll(unplacedSection(items(field(plan, "unplaced"))));
        lines.add("</body>");
        lines.add("</html>");
        return String.join("\n", lines) + "\n";
    }

    // ----------------------------------------------------------------------------------- CSV

    private static List<Object> stepRow(Object workOrder, Object container, Object line) {
        Object reference = field(line, "placement");
        Object ticks = field(reference, "position_ticks");
        Object position = field(line, "position");
        Object dimensions = field(line, "dimensions");
        List<Object> row = new ArrayList<>(CSV_COLUMNS.size());
        row.add("step");
        row.add(field(reference, "container_index"));
        row.add(field(container, "container_type"));
        row.add(member(line, "sequence"));
        row.add(field(reference, "item_type"));
        row.add(field(reference, "orientation"));
        for (String axis : List.of("x", "y", "z")) {
            row.add(field(ticks, axis));
        }
        for (String axis : List.of("x", "y", "z")) {
            row.add(field(position, axis));
        }
        for (String axis : List.of("length", "width", "height")) {
            row.add(field(dimensions, axis));
        }
        row.add(field(workOrder, "length_unit"));
        row.add(null);
        row.add(null);
        return row;
    }

    private static String csvField(Object value) {
        String text = text(value);
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == ',' || character == '"' || character == '\r' || character == '\n') {
                return '"' + text.replace("\"", "\"\"") + '"';
            }
        }
        return text;
    }

    // ---------------------------------------------------------------------------------- HTML

    private static List<String> factRows(Object document, Object plan, Object provenance) {
        Object facts = field(plan, "facts");
        Object replay = field(provenance, "replay");
        Object solver = field(provenance, "solver");
        Object feasibility = field(facts, "feasibility");
        Object because = field(replay, "because");
        String replayText = escape(field(replay, "level")) + (isTruthy(because) ? " (" + escape(because) + ")" : "");
        String solverText = DASH;
        if (isTruthy(solver)) {
            solverText = escape(field(solver, "profile")) + " / " + escape(field(solver, "solver")) + " / seed "
                    + escape(field(solver, "seed"));
        }
        List<String> catalogs = new ArrayList<>();
        for (Object catalog : items(field(provenance, "catalog_versions_used"))) {
            catalogs.add(escape(member(catalog, "catalog_id")) + " v" + escape(member(catalog, "version")));
        }
        String catalogText = String.join(", ", catalogs);
        List<String> terms = new ArrayList<>();
        for (Object term : items(field(facts, "score"))) {
            terms.add(escape(term));
        }
        List<String[]> rows = List.of(
                new String[] {"Status", escape(field(facts, "status"))},
                new String[] {"Objective", orDash(field(plan, "objective"))},
                new String[] {"Containers", escape(field(facts, "container_count"))},
                new String[] {"Score", "[" + String.join(", ", terms) + "]"},
                new String[] {"Feasibility", orDash(feasibility instanceof Map ? member(feasibility, "code") : null)},
                new String[] {"Replay", replayText},
                new String[] {"Solver", solverText},
                new String[] {"Catalogs", catalogText.isEmpty() ? DASH : catalogText},
                new String[] {"Packvium", escape(field(document, "suite_version"))});
        List<String> html = new ArrayList<>(rows.size());
        for (String[] row : rows) {
            html.add("<tr><td>" + row[0] + "</td><td>" + row[1] + "</td></tr>");
        }
        return html;
    }

    private static List<String> containerSection(Object planContainer, Object container, Object workOrder) {
        Object facts = field(planContainer, "facts");
        String weightUnit = escape(field(workOrder, "weight_unit"));
        String lengthUnit = escape(field(workOrder, "length_unit"));
        List<String> lines = new ArrayList<>();
        lines.add("<section class=\"container\">");
        lines.add("<h2>Container " + ordinal(field(container, "container_index")) + ": "
                + orDash(field(container, "container_type")) + "</h2>");
        lines.add("<p>Payload " + escape(field(container, "payload_weight")) + " " + weightUnit + " &middot; "
                + "Gross " + escape(field(container, "gross_weight")) + " " + weightUnit + " &middot; "
                + "Utilization " + orDash(field(facts, "volume_utilization")) + "</p>");
        if ("loading".equals(field(planContainer, "order"))) {
            lines.add("<p>Order: loading. Follow the steps in sequence.</p>");
        } else {
            lines.add("<p>Order: unavailable. No safe loading order was supplied, so the steps are not numbered.</p>");
        }
        lines.add("<table>");
        lines.add("<thead><tr><th>Step</th><th>Item</th><th>Orientation</th><th>Position x, y, z (" + lengthUnit
                + ")</th><th>Size l &times; w &times; h (" + lengthUnit + ")</th><th>Done</th></tr></thead>");
        lines.add("<tbody>");
        for (Object line : items(field(container, "lines"))) {
            lines.add(stepHtmlRow(line));
        }
        lines.add("</tbody>");
        lines.add("</table>");
        lines.add("</section>");
        return lines;
    }

    private static String stepHtmlRow(Object line) {
        Object reference = field(line, "placement");
        Object position = field(line, "position");
        Object size = field(line, "dimensions");
        Object sequence = ((Map<?, ?>) line).containsKey("sequence") ? member(line, "sequence") : "";
        return "<tr><td>" + escape(sequence) + "</td><td>" + escape(field(reference, "item_type")) + "</td>"
                + "<td>" + escape(field(reference, "orientation")) + "</td>"
                + "<td>" + escape(field(position, "x")) + ", " + escape(field(position, "y")) + ", "
                + escape(field(position, "z")) + "</td>"
                + "<td>" + escape(field(size, "length")) + " &times; " + escape(field(size, "width")) + " &times; "
                + escape(field(size, "height")) + "</td>"
                + "<td>&#9744;</td></tr>";
    }

    private static List<String> unplacedSection(List<?> entries) {
        List<String> lines = new ArrayList<>(List.of("<section>", "<h2>Not packed</h2>"));
        if (entries.isEmpty()) {
            lines.add("<p>Every item was packed.</p>");
            lines.add("</section>");
            return lines;
        }
        lines.add("<table>");
        lines.add("<thead><tr><th>Item</th><th>Reason</th><th>Proof</th></tr></thead>");
        lines.add("<tbody>");
        for (Object entry : entries) {
            Object facts = field(entry, "facts");
            lines.add("<tr><td>" + orDash(field(facts, "item_type")) + "</td><td>" + orDash(field(facts, "reason"))
                    + "</td><td>" + orDash(field(facts, "proof_level")) + "</td></tr>");
        }
        lines.add("</tbody>");
        lines.add("</table>");
        lines.add("</section>");
        return lines;
    }

    private static String escape(Object value) {
        String text = text(value);
        StringBuilder escaped = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> escaped.append(character);
            }
        }
        return escaped.toString();
    }

    private static String orDash(Object value) {
        return value == null ? DASH : escape(value);
    }

    /**
     * A container's one-based number on the sheet. Computed, so it is checked like
     * {@link #text}: {@code 0.5 + 1} and {@code true + 1} would each print differently in one
     * engine and be refused in another.
     */
    private static String ordinal(Object index) {
        if (!(index instanceof Long number)) {
            throw new OperationalArtifactException("invalid_value", "container index " + index + " is not an integer");
        }
        return Long.toString(Math.addExact(number, 1L));
    }

    // ------------------------------------------------------------------------------- helpers

    /**
     * The one rendering every engine shares: a string as itself, an integer in decimal, null as
     * nothing. A boolean, a float, a list or an object has a different default spelling in each
     * language, so it is refused rather than printed four ways.
     */
    private static String text(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Long || value instanceof BigInteger) {
            return value.toString();
        }
        throw new OperationalArtifactException("invalid_value",
                "a " + value.getClass().getSimpleName() + " has no single rendering in a work order");
    }

    /**
     * Python's truthiness for a JSON value: null, false, zero, an empty string, an empty list and
     * an empty object are false. Read back from canonical text, so a zero is always a
     * {@code Long}.
     */
    private static boolean isTruthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Long number) {
            return number != 0;
        }
        if (value instanceof String text) {
            return !text.isEmpty();
        }
        if (value instanceof List<?> elements) {
            return !elements.isEmpty();
        }
        if (value instanceof Map<?, ?> members) {
            return !members.isEmpty();
        }
        return true;
    }

    /** An export reads only a document it knows how to read, and says so when it cannot. */
    private static Object requireArtifact(Object artifact) {
        Object found = artifact instanceof Map<?, ?> document ? document.get("format") : null;
        if (!OperationalArtifact.FORMAT.equals(found)) {
            String named = found instanceof String text ? "'" + text + "'" : "none";
            throw new OperationalArtifactException("unknown_format",
                    "cannot export format " + named + "; this exporter reads " + OperationalArtifact.FORMAT);
        }
        return artifact;
    }

    /**
     * A member a v1 document always has. Its absence means the document is not one this exporter
     * knows how to read.
     */
    private static Object field(Object object, String name) {
        if (!(object instanceof Map<?, ?> members) || !members.containsKey(name)) {
            throw new OperationalArtifactException("unknown_format",
                    "the document has no " + name + " where the format requires one");
        }
        return members.get(name);
    }

    /** Python's {@code mapping.get(name)}: null when the member is absent or the value is no object. */
    private static Object member(Object object, String name) {
        return object instanceof Map<?, ?> members ? members.get(name) : null;
    }

    private static List<?> items(Object value) {
        if (!(value instanceof List<?> elements)) {
            throw new OperationalArtifactException("unknown_format",
                    "the document has an object where the format requires a list");
        }
        return elements;
    }
}
