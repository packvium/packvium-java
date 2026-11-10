package com.packvium.result;

import java.util.LinkedHashMap;
import java.util.Map;

/** How one portfolio start ended, and whether its answer is the one returned. */
public record StartRecord(
        String id,
        boolean started,
        boolean completed,
        boolean truncated,
        boolean selected,
        boolean globalDeadlineReached) {

    public StartRecord withSelected(boolean value) {
        return new StartRecord(id, started, completed, truncated, value, globalDeadlineReached);
    }

    public StartRecord withGlobalDeadline(boolean reached) {
        return new StartRecord(id, started, completed, truncated, selected, reached);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("id", id);
        rendered.put("started", started);
        rendered.put("completed", completed);
        rendered.put("truncated", truncated);
        rendered.put("selected", selected);
        rendered.put("global_deadline_reached", globalDeadlineReached);
        return rendered;
    }
}
