package com.packvium.revisions;

/** One way a chain fails its audit, anchored on the revision where it shows. */
public record RevisionIssue(String code, long revision, String detail) {
}
