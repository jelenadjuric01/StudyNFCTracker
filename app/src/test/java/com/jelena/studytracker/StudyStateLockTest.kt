package com.jelena.studytracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests verifying that [StudyStateLock] guarantees mutual exclusion across concurrent execution threads.
 */
class StudyStateLockTest {

    @Test
    fun `given multiple concurrent threads when synchronized on StudyStateLock then critical sections do not overlap`() {
        val threadCount = 10
        val iterationsPerThread = 100
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        val inCriticalSection = AtomicBoolean(false)
        val overlapDetected = AtomicBoolean(false)
        val executionCount = AtomicInteger(0)

        repeat(threadCount) {
            executor.execute {
                try {
                    repeat(iterationsPerThread) {
                        synchronized(StudyStateLock) {
                            if (inCriticalSection.getAndSet(true)) {
                                overlapDetected.set(true)
                            }
                            Thread.sleep(1)
                            executionCount.incrementAndGet()
                            inCriticalSection.set(false)
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        val completedInTime = latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue("All threads must finish within timeout", completedInTime)
        assertEquals("Critical sections must not overlap", false, overlapDetected.get())
        assertEquals(
            "Every iteration must execute under the lock",
            threadCount * iterationsPerThread,
            executionCount.get(),
        )
    }

    @Test
    fun `given concurrent write and read workers when synchronized on StudyStateLock then operations are thread safe`() {
        val totalSteps = 100
        val executor = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(totalSteps * 2)
        val stateHistory = mutableListOf<Int>()
        val observedSnapshotSizes = mutableListOf<Int>()

        repeat(totalSteps) { step ->
            executor.execute {
                synchronized(StudyStateLock) {
                    stateHistory.add(step)
                }
                latch.countDown()
            }
            executor.execute {
                synchronized(StudyStateLock) {
                    val snapshot = ArrayList(stateHistory)
                    observedSnapshotSizes.add(snapshot.size)
                }
                latch.countDown()
            }
        }

        val completed = latch.await(5, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue("Tasks should complete within timeout", completed)
        synchronized(StudyStateLock) {
            assertEquals("All write operations should be recorded without loss", totalSteps, stateHistory.size)
            assertEquals("All read operations should capture snapshot sizes", totalSteps, observedSnapshotSizes.size)
            assertTrue("Every snapshot size must be between 0 and totalSteps", observedSnapshotSizes.all { it in 0..totalSteps })
        }
    }
}
