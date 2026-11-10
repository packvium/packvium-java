package com.packvium.serialization;

import com.packvium.constraint.AxleLoad;
import com.packvium.constraint.LoadCalculator;
import com.packvium.constraint.VolumeReserve;
import com.packvium.domain.LatticeSummary;
import com.packvium.domain.PackedContainer;
import com.packvium.domain.Placement;
import com.packvium.domain.UnpackedItem;
import com.packvium.result.PackingResult;
import com.packvium.support.Formats;
import com.packvium.unit.Length;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The wire form of a packing result; key order follows the reference engines' output. */
public final class ResultEncoder {
    private final String lengthUnit;
    private final String weightUnit;

    public ResultEncoder(String lengthUnit, String weightUnit) {
        this.lengthUnit = lengthUnit;
        this.weightUnit = weightUnit;
    }

    public Map<String, Object> encode(PackingResult result) {
        return encode(result, true);
    }

    private Map<String, Object> encode(PackingResult result, boolean includeAlternatives) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", result.status().wireName());
        out.put("feasibility", result.feasibility().toMap());
        out.put("termination", result.termination().toMap());
        out.put("optimality", result.optimality().toMap());
        out.put("complete", result.complete());
        out.put("objective", result.objective());
        out.put("algorithm", algorithm(result));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("container_count", (long) result.containers().size());
        summary.put("packed_item_count", result.packedItemCount());
        summary.put("unpacked_item_count", (long) result.unpacked().size());
        out.put("summary", summary);
        out.put("score", result.score());
        out.put("containers", result.containers().stream().map(this::container).toList());
        out.put("unpacked_items", result.unpacked().stream().map(ResultEncoder::unpacked).toList());
        out.put("catalog_versions_used", List.of());
        out.put("warnings", result.warnings());
        out.put("alternatives", includeAlternatives
                ? result.alternatives().stream().map(alternative -> encode(alternative, false)).toList()
                : List.of());
        return out;
    }

    private static Map<String, Object> algorithm(PackingResult result) {
        var report = result.algorithm();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profile", report.profile());
        out.put("solver", report.solver());
        out.put("duration_ms", report.durationMs());
        out.put("seed", report.seed());
        out.put("time_limit_reached", report.timeLimitReached());
        out.put("effort_limit_reached", report.effortLimitReached());
        out.put("candidates_evaluated", report.candidatesEvaluated());
        out.put("placements_attempted", report.placementsAttempted());
        out.put("metrics", report.metrics().toMap());
        return out;
    }

    private Map<String, Object> container(PackedContainer packed) {
        var container = packed.container();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", packed.id());
        out.put("container_type", container.id());
        out.put("inner_dimensions", container.innerDimensions().toMap(lengthUnit));
        out.put("outer_dimensions", container.outerDimensions().orElse(container.innerDimensions()).toMap(lengthUnit));
        out.put("payload_weight", packed.payloadWeight().toMap(weightUnit));
        out.put("gross_weight", packed.grossWeight().toMap(weightUnit));
        out.put("used_volume_ticks3", packed.usedVolume().toString());
        out.put("volume_utilization", Formats.fixed6(packed.utilization()));
        out.put("centre_of_mass_offset_ppm", packed.centreOfMassOffsetPpm());
        out.put("void_fill_reserve_ticks3", VolumeReserve.reserved(container).toString());
        List<Object> placements = new ArrayList<>();
        for (Placement placement : packed.placements()) {
            placements.add(placement(placement));
        }
        out.put("placements", placements);
        packed.latticeSummary().ifPresent(summary -> out.put("lattice_summary", lattice(summary)));
        container.axles().ifPresent(axles -> {
            var reaction = AxleLoad.reactions(axles, LoadCalculator.units(packed.placements()),
                    container.tareWeight().ticks(), container.innerDimensions().length().ticks());
            Map<String, Object> reactions = new LinkedHashMap<>();
            reactions.put("basis", "gross");
            // Decimal strings: the numerators are products of ticks and outgrow every engine's integers.
            reactions.put("denominator", reaction.denominator().toString());
            reactions.put("front_numerator", reaction.frontNumerator().toString());
            reactions.put("rear_numerator", reaction.rearNumerator().toString());
            out.put("axle_reactions", reactions);
        });
        return out;
    }

    private Map<String, Object> placement(Placement placement) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("item_id", placement.instance().id());
        out.put("item_type", placement.instance().item().id());
        out.put("position", placement.position().toMap(lengthUnit));
        out.put("dimensions", placement.dimensions().toMap(lengthUnit));
        out.put("orientation", placement.rotation().name());
        out.put("support_ratio", Formats.fixed6(placement.supportRatio()));
        out.put("top_load", placement.topLoad().toMap(weightUnit));
        return out;
    }

    /**
     * Compact form: present only when the lattice fast path built the container, so a
     * default request's output is byte for byte unchanged.
     */
    private Map<String, Object> lattice(LatticeSummary summary) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("item_type", summary.itemType());
        out.put("orientation", summary.rotation().name());
        out.put("physical_dimensions", summary.physical().toMap(lengthUnit));
        out.put("envelope_dimensions", summary.envelope().toMap(lengthUnit));
        out.put("nx", summary.nx());
        out.put("ny", summary.ny());
        out.put("layers_used", summary.layersUsed());
        out.put("layer_step", new Length(summary.layerStep()).toMap(lengthUnit));
        out.put("count", summary.count());
        return out;
    }

    private static Map<String, Object> unpacked(UnpackedItem item) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("item_id", item.instance().id());
        out.put("item_type", item.instance().item().id());
        out.put("reason", item.reason());
        out.put("details", item.details());
        out.put("proof", item.proof().toMap());
        return out;
    }
}
