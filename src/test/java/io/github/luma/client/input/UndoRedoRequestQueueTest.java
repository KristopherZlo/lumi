package io.github.luma.client.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UndoRedoRequestQueueTest {

    private static final UndoRedoRequestQueue.Scope MAIN = new UndoRedoRequestQueue.Scope("overworld", "project-main");
    private static final UndoRedoRequestQueue.Scope OTHER = new UndoRedoRequestQueue.Scope("nether", "project-main");

    @Test
    void keepsMixedIntentsInPressOrder() {
        UndoRedoRequestQueue queue = new UndoRedoRequestQueue();

        assertTrue(queue.offer(MAIN, UndoRedoRequestQueue.Intent.UNDO));
        assertTrue(queue.offer(MAIN, UndoRedoRequestQueue.Intent.REDO));
        assertTrue(queue.offer(MAIN, UndoRedoRequestQueue.Intent.UNDO));

        assertEquals(UndoRedoRequestQueue.Intent.UNDO, queue.poll(MAIN));
        assertEquals(UndoRedoRequestQueue.Intent.REDO, queue.poll(MAIN));
        assertEquals(UndoRedoRequestQueue.Intent.UNDO, queue.poll(MAIN));
        assertTrue(queue.isEmpty(MAIN));
    }

    @Test
    void rejectsOverflowAfterSixteenQueuedRequests() {
        UndoRedoRequestQueue queue = new UndoRedoRequestQueue();

        for (int index = 0; index < UndoRedoRequestQueue.MAX_REQUESTS; index++) {
            assertTrue(queue.offer(MAIN, UndoRedoRequestQueue.Intent.UNDO));
        }

        assertFalse(queue.offer(MAIN, UndoRedoRequestQueue.Intent.REDO));
        assertEquals(UndoRedoRequestQueue.MAX_REQUESTS, queue.size(MAIN));
    }

    @Test
    void unavailableIntentCanReturnToFrontWithoutChangingOrder() {
        UndoRedoRequestQueue queue = new UndoRedoRequestQueue();
        queue.offer(MAIN, UndoRedoRequestQueue.Intent.REDO);
        queue.offer(MAIN, UndoRedoRequestQueue.Intent.UNDO);

        UndoRedoRequestQueue.Intent intent = queue.poll(MAIN);
        queue.offerFirst(MAIN, intent);

        assertEquals(UndoRedoRequestQueue.Intent.REDO, queue.poll(MAIN));
        assertEquals(UndoRedoRequestQueue.Intent.UNDO, queue.poll(MAIN));
        assertNull(queue.poll(MAIN));
    }

    @Test
    void keepsSeparateLimitsPerWorldProjectScope() {
        UndoRedoRequestQueue queue = new UndoRedoRequestQueue();

        for (int index = 0; index < UndoRedoRequestQueue.MAX_REQUESTS; index++) {
            assertTrue(queue.offer(MAIN, UndoRedoRequestQueue.Intent.UNDO));
        }
        assertTrue(queue.offer(OTHER, UndoRedoRequestQueue.Intent.REDO));

        assertEquals(UndoRedoRequestQueue.MAX_REQUESTS, queue.size(MAIN));
        assertEquals(1, queue.size(OTHER));
        assertEquals(UndoRedoRequestQueue.Intent.REDO, queue.poll(OTHER));
    }
}
