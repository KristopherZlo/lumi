package io.github.luma.minecraft.world;

public final class SectionApplySafetyClassifier {

    static final int NATIVE_DENSE_THRESHOLD = 64;
    static final int CONTAINER_REWRITE_THRESHOLD = 256;

    public SectionApplySafetyProfile classify(LumiSectionBuffer buffer, boolean fullSection) {
        if (buffer == null || buffer.changedCellCount() <= 0) {
            return SectionApplySafetyProfile.directSection("empty-section");
        }
        if (fullSection || buffer.changedCellCount() >= CONTAINER_REWRITE_THRESHOLD) {
            return SectionApplySafetyProfile.sectionRewrite(fullSection ? "full-section" : "rewrite-dense-section");
        }
        if (buffer.changedCellCount() >= NATIVE_DENSE_THRESHOLD) {
            return SectionApplySafetyProfile.nativeSection("dense-section");
        }
        return SectionApplySafetyProfile.directSection("sparse-section");
    }
}
