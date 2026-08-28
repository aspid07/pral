package com.lowcode.platform.execution;

/**
 * Внутренний сигнал прерывания run() по запросу пользователя (Стоп, либо
 * Стоп во время Паузы — см. RunControl/ExecutionEngine.checkpoint). НЕ
 * бизнес-ошибка — run()'s catch ловит её отдельно, ДО общего
 * catch(RuntimeException), чтобы она не попала в RUN_ERROR/Status.FAILED,
 * а привела к RUN_STOPPED/Status.STOPPED (см. ExecutionEngine.run,
 * RunService.finish).
 */
final class StopRequestedException extends RuntimeException {
    StopRequestedException() {
        // Без сообщения/причины/стектрейса/подавления — чистый control-flow
        // сигнал: заполнение стектрейса не бесплатно, а здесь он никому не
        // нужен (это не диагностика бага, это ожидаемый штатный путь), и не
        // должен всплывать в логах как настоящая ошибка.
        super(null, null, false, false);
    }
}
