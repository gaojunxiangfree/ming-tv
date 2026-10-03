package com.seanming.player.util;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 线程调度: IO 线程池 + 主线程回调 */
public class ThreadUtils {

    private static final ExecutorService IO = Executors.newFixedThreadPool(6);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static void io(Runnable r) { IO.execute(r); }

    public static void main(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else MAIN.post(r);
    }

    /** 在 IO 线程执行 task, 主线程回调 */
    public static <T> void call(Supplier<T> task, Callback<T> callback) {
        IO.execute(() -> {
            T result;
            try {
                result = task.get();
            } catch (Throwable t) {
                MAIN.post(() -> callback.onError(t));
                return;
            }
            T finalResult = result;
            MAIN.post(() -> callback.onResult(finalResult));
        });
    }

    public interface Supplier<T> { T get() throws Exception; }

    public interface Callback<T> {
        void onResult(T result);
        default void onError(Throwable t) {}
    }
}
