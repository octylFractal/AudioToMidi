package me.kenzierocks.a2m.v2;

import static com.google.common.base.Preconditions.checkNotNull;

import java.nio.DoubleBuffer;
import java.util.Iterator;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bytedeco.javacpp.fftw3;
import org.bytedeco.javacpp.fftw3.fftw_plan;

import com.google.auto.value.AutoValue;
import com.google.common.base.Throwables;
import com.google.common.collect.AbstractIterator;

public class ParallelWindower {

    private static final class Plan {

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
        protected void finalize() throws Throwable {
            fftw3.fftw_destroy_plan(plan);
        }

    }

    @AutoValue
    public abstract static class TaskResult {

        public static TaskResult wrap(double[] p, double[] ph1) {
            return new AutoValue_ParallelWindower_TaskResult(p, ph1);
        }

        TaskResult() {
        }

        @SuppressWarnings("mutable")
        public abstract double[] p();

        @SuppressWarnings("mutable")
        public abstract double[] ph1();

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
    private final DoubleBuffer inputData;
    private final int len;
    private final int hop;
    private final double den;

    public ParallelWindower(Window window, DoubleBuffer inputData, int len, int hop) {
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
        BlockingDeque<Future<TaskResult>> bufferQueue = new LinkedBlockingDeque<>(Runtime.getRuntime().availableProcessors() * 3);
        AtomicBoolean complete = new AtomicBoolean(false);
        submitSubmitterTask(exec, bufferQueue, complete);
        return new AbstractIterator<Future<TaskResult>>() {

            private BlockingDeque<Future<TaskResult>> ref = bufferQueue;

            @Override
            protected Future<TaskResult> computeNext() {
                if (complete.get()) {
                    // null out for GC
                    ref = null;
                    return endOfData();
                }
                try {
                    return ref.takeFirst();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }

        };
    }

    private void submitSubmitterTask(ExecutorService exec, BlockingDeque<Future<TaskResult>> bufferQueue, AtomicBoolean complete) {
        Thread submitter = new Thread(() -> {
            while (inputData.remaining() > len) {
                int lim = inputData.limit();
                // slice out [pos, pos + len]
                inputData.limit(inputData.position() + len);
                DoubleBuffer task = inputData.slice();
                // reset limit
                inputData.limit(lim);
                // move up by hop
                inputData.position(inputData.position() + hop);

                try {
                    bufferQueue.putLast(exec.submit(new Task(window, task, den)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
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
