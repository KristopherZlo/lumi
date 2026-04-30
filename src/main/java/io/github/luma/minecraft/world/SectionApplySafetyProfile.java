package io.github.luma.minecraft.world;

public record SectionApplySafetyProfile(
        SectionApplyPath path,
        String reason
) {

    public SectionApplySafetyProfile {
        path = path == null ? SectionApplyPath.DIRECT_SECTION : path;
        reason = reason == null ? "" : reason;
    }

    static SectionApplySafetyProfile nativeSection(String reason) {
        return new SectionApplySafetyProfile(SectionApplyPath.SECTION_NATIVE, reason);
    }

    static SectionApplySafetyProfile sectionRewrite(String reason) {
        return new SectionApplySafetyProfile(SectionApplyPath.SECTION_REWRITE, reason);
    }

    static SectionApplySafetyProfile directSection(String reason) {
        return new SectionApplySafetyProfile(SectionApplyPath.DIRECT_SECTION, reason);
    }

    static SectionApplySafetyProfile vanilla(String reason) {
        return new SectionApplySafetyProfile(SectionApplyPath.VANILLA, reason);
    }
}
