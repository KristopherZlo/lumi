/**
 * Minecraft-side capture adapters for player edits.
 *
 * <p>This package translates block mutations into tracked history while
 * respecting mutation sources so that restore or automation activity does not
 * recurse back into player history.
 */
package io.github.luma.minecraft.capture;
