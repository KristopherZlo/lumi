/**
 * Domain records and value objects used across Luma.
 *
 * <p>This package contains immutable persisted state, immutable summaries, and a
 * small number of tightly scoped mutable runtime structures such as
 * {@link io.github.luma.domain.model.TrackedChangeBuffer}. It should remain free
 * from Minecraft event wiring and file-system concerns.
 */
package io.github.luma.domain.model;
