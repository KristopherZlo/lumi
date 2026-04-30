package io.github.luma.minecraft.world;

public final class SectionApplySafetyClassifier {

    static final int NATIVE_DENSE_THRESHOLD = 64;

    public SectionApplySafetyProfile classify(LumiSectionBuffer buffer, boolean fullSection) {
        if (buffer == null || buffer.changedCellCount() <= 0) {
            return SectionApplySafetyProfile.directSection("empty-section");
        }
        if (fullSection || buffer.changedCellCount() >= NATIVE_DENSE_THRESHOLD) {
            return SectionApplySafetyProfile.nativeSection(fullSection ? "full-section" : "dense-section");
        }
        return SectionApplySafetyProfile.directSection("sparse-section");
    }
}
