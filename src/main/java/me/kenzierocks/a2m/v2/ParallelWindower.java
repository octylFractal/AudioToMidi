/*
 * This file is part of AudioToMidi, licensed under the MIT License (MIT).
 *
 * Copyright (c) TechShroom Studios <https://techshroom.com>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package me.kenzierocks.a2m.v2;

import static com.google.common.base.Preconditions.checkNotNull;

import java.nio.DoubleBuffer;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bytedeco.javacpp.fftw3;
import org.bytedeco.javacpp.fftw3.fftw_plan;
import org.lwjgl.BufferUtils;

import com.google.auto.value.AutoValue;
import com.google.common.base.Throwables;
import com.google.common.collect.AbstractIterator;

public class ParallelWindower {

    private static final class Plan implements AutoCloseable {

        private static final ThreadLocal<Plan> PLANS = new ThreadLocal<>();

        public static Plan getPlan(int length) {
            Plan p = PLANS.get();
            if (p == null) {
                p = new Plan(length);
                PLANS.set(p);
            }
            return p;
        }

        private final fftw_plan plan;
        private final DoubleBuffer input;
        private final DoubleBuffer output;

        private Plan(int length) {
            input = fftw3.fftw_alloc_real(length).limit(length).asBuffer();
            checkNotNull(input, "failed to allocate fftw input");
            output = fftw3.fftw_alloc_real(length).limit(length).asBuffer();
            checkNotNull(output, "failed to allocate fftw output");
            // planner is not thread-safe -- must be sync
            synchronized (Plan.class) {
                plan = fftw3.fftw_plan_r2r_1d(length, input, output, fftw3.FFTW_R2HC, (int) fftw3.FFTW_ESTIMATE);
                checkNotNull(plan, "failed to allocate fftw plan???");
            }
        }

        @Override
        public void close() {
            fftw3.fftw_destroy_plan(plan);
        }

    }

    @AutoValue
    public abstract static class TaskResult {

        public static final class DArr {

            public final double[] array;

            public DArr(double[] array) {
                this.array = array;
            }

        }

        public static TaskResult wrap(double[] p, double[] ph1) {
            return new AutoValue_ParallelWindower_TaskResult(new DArr(p), new DArr(ph1));
        }

        TaskResult() {
        }

        public abstract DArr p();

        public abstract DArr ph1();

    }

    private static final class Task implements Callable<TaskResult> {

        private final Window window;
        private final DoubleBuffer input;
        private final double den;

        public Task(Window window, DoubleBuffer input, double den) {
            this.window = window;
            this.input = input;
            this.den = den;
        }

        @Override
        public TaskResult call() throws Exception {
            int len = input.remaining();
            Plan plan = Plan.getPlan(len);
            plan.input.position(0);
            plan.output.position(0);

            window.windowing(len, input, 1, plan.input);

            fftw_execute(plan.plan);

            double[] p = new double[len];
            double[] ph1 = new double[len];
            HC.to_polar2(len, plan.output, 0, den, p, ph1);
            return TaskResult.wrap(p, ph1);
        }

    }

    private final Window window;
    private final DoubleSource inputData;
    private final int len;
    private final int hop;
    private final double den;

    public ParallelWindower(Window window, DoubleSource inputData, int len, int hop) {
        this.window = window;
        this.inputData = inputData;
        this.len = len;
        this.hop = hop;
        this.den = window.init_den(len);
    }

    public Iterator<TaskResult> process(ExecutorService exec) {
        Iterator<Future<TaskResult>> bufIter = submitBuffers(exec);
        return new AbstractIterator<ParallelWindower.TaskResult>() {

            @Override
            protected TaskResult computeNext() {
                if (!bufIter.hasNext()) {
                    return endOfData();
                }
                try {
                    return bufIter.next().get();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    Throwables.throwIfUnchecked(cause);
                    throw new RuntimeException(cause);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }
        };
    }

    private Iterator<Future<TaskResult>> submitBuffers(ExecutorService exec) {
        // assumes good usage is 3x processor size
        BlockingQueue<Future<TaskResult>> bufferQueue = new LinkedBlockingQueue<>(1024);
        AtomicBoolean complete = new AtomicBoolean(false);
        submitSubmitterTask(exec, bufferQueue, complete);
        return new AbstractIterator<Future<TaskResult>>() {

            private BlockingQueue<Future<TaskResult>> ref = bufferQueue;

            @Override
            protected Future<TaskResult> computeNext() {
                if (ref.isEmpty() && complete.get()) {
                    // null out for GC
                    ref = null;
                    return endOfData();
                }
                try {
                    return ref.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }

        };
    }

    private void submitSubmitterTask(ExecutorService exec, BlockingQueue<Future<TaskResult>> bufferQueue, AtomicBoolean complete) {
        Thread submitter = new Thread(() -> {
            // We read in a chunk at a time, storing it in a large buffer (16MB)
            // We move the data back occasionally, which is fine because it's
            // a small copy.

            // This buffer has the following state:
            // - Position: current index of slicer, will cut [pos, pos+len]
            // - Limit: current max readable bytes, usually == capacity unless
            // last loop
            // - Capacity: max storage for bytes, as is typical
            DoubleBuffer movingBuffer = BufferUtils.createDoubleBuffer(64 * len);

            boolean moreData = true;
            while (moreData) {
                // First off, fill the buffer all the way
                if (!inputData.fillBuffer(movingBuffer, movingBuffer.remaining())) {
                    // we hit EOF, stop on next loop
                    moreData = false;
                }
                // position is at 0, where it should be

                // now, process until we need to squash it again
                while (movingBuffer.remaining() > len) {
                    // slice out [pos, pos + len]
                    DoubleBuffer task = BufferUtils.createDoubleBuffer(len);
                    DoubleBuffer mbCopySlice = movingBuffer.slice();
                    mbCopySlice.limit(len);
                    task.put(mbCopySlice);
                    task.flip();
                    // move back down for next window
                    movingBuffer.position(movingBuffer.position() + hop);

                    try {
                        bufferQueue.put(exec.submit(new Task(window, task, den)));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                }
                // then copy it down to the bottom and roll again
                // NB: compact leaves it ready for the #fill above!
                movingBuffer.compact();
            }
            complete.set(true);
        }, "submitter-thread");
        submitter.start();
    }

    // split out for profiling purposes
    private static void fftw_execute(fftw_plan plan) {
        fftw3.fftw_execute(plan);
    }

}
