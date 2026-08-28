package com.lowcode.platform.execution;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Единственный канал связи между HTTP-потоком (пользователь нажал Пауза/
 * Продолжить/Стоп — см. RunService.requestPause/requestResume/requestStop) и
 * виртуальным потоком, который реально выполняет ExecutionEngine.run() для
 * этого runId (см. RunService.runExecutor). Никакого состояния в БД здесь
 * нет намеренно — persist/публикацию WS-событий делает сам ExecutionEngine
 * в момент, когда он ДЕЙСТВИТЕЛЬНО останавливается на checkpoint'е, а не в
 * момент, когда пользователь нажал кнопку (см. ExecutionEngine.checkpoint) —
 * иначе UI показывал бы "на паузе" чуть раньше, чем движок реально дошёл до
 * точки, на которой он встал.
 *
 * Package-private: наружу (RunController/RunService) торчат только глаголы
 * request*(); сам объект и его состояние — деталь исполнения, не API.
 */
final class RunControl {

    private final Lock lock = new ReentrantLock();
    private final Condition stateChanged = lock.newCondition();
    private volatile boolean pauseRequested = false;
    private volatile boolean stopRequested = false;

    void requestPause() {
        lock.lock();
        try {
            pauseRequested = true;
        } finally {
            lock.unlock();
        }
    }

    void requestResume() {
        lock.lock();
        try {
            pauseRequested = false;
            stateChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }

    void requestStop() {
        lock.lock();
        try {
            stopRequested = true;
            // Будим поток, ЕСЛИ он сейчас ждёт на паузе — иначе Стоп во время
            // Паузы завис бы до бесконечности: run() узнал бы о нём только на
            // следующем checkpoint(), а следующего просто не будет, поток
            // спит в awaitResumeOrStop().
            stateChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }

    boolean isStopRequested() {
        return stopRequested;
    }

    boolean isPauseRequested() {
        return pauseRequested;
    }

    /**
     * Блокирует ВЫЗЫВАЮЩИЙ (движковый) поток, пока паузу не снимут (resume)
     * либо не попросят остановиться (stop) — вызывается только из
     * ExecutionEngine.checkpoint(), никогда с HTTP-потока.
     */
    void awaitResumeOrStop() {
        lock.lock();
        try {
            while (pauseRequested && !stopRequested) {
                try {
                    stateChanged.await();
                } catch (InterruptedException e) {
                    // JVM shutdown (RunService.shutdownRunExecutor -> shutdownNow())
                    // прерывает виртуальный поток, пока он ждёт здесь на паузе —
                    // трактуем это как запрос остановки: иначе @PreDestroy никогда
                    // не дождался бы завершения ЭТОГО run (тот же принцип уже
                    // применён к самому executor'у, см. ревью CTO, п.3.5).
                    stopRequested = true;
                    Thread.currentThread().interrupt();
                }
            }
        } finally {
            lock.unlock();
        }
    }
}
