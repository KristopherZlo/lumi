# Compare highlight preparation

`ClientCompareStore` keeps one immutable input while H changes visibility.
`LumiPendingChangeOverlay` retains the renderer input until Compare is replaced,
cleared, or disconnected. H keeps the bounded GPU cache, so showing an unchanged
highlight again does not repeat preparation or uploads.

`CompareOverlayGeometry` reports completed blocks in its two background passes.
`LumiCompareOverlayRenderer` adds the visible batch uploads to this local progress.
`HighlightPreparationProgress` is an immutable client value; it does not create a
server history operation. Completed, hidden, and failed preparations have no
active progress indicator.

The first highlight is presented after its visible batches are ready. Subsequent
camera movement keeps the existing highlight visible while new batches upload.
Preparation remains asynchronous. Uploads retain the two-batch and 4 ms launch
budgets, and the resident cache retains its 128 MiB limit and exact transient
fallback. No changed blocks or interior x-ray edges are omitted.

Unit coverage includes immutable input retention, both geometry progress passes,
and valid progress bounds. `COMPARE_RENDER` exercises the registered owner and
actual H binding across three hide/show cycles, checks retained bytes and immediate
readiness, and verifies that clearing Compare releases the cache.
