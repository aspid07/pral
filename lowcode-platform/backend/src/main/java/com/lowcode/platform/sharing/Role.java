package com.lowcode.platform.sharing;

/**
 * Общий для Collaborator (роль на Scenario) и ProjectMember (роль на Project).
 * Раньше был вложен в Collaborator — вынесен, потому что PermissionService
 * сравнивает роль с обоих уровней и берёт максимум — нужен один и тот же тип.
 *
 * Ревью CTO, п.2.5: раньше уровень определялся порядком объявления констант
 * (ordinal()/compareTo()) — перестановка строк в этом файле молча меняла бы
 * правила авторизации, и ни один тест не поймал бы это как проблему
 * безопасности (просто начал бы падать где-то в другом месте). Явный level —
 * переставить объявления теперь можно без последствий.
 */
public enum Role {
    READER(10), EDITOR(20), OWNER(30);

    private final int level;

    Role(int level) {
        this.level = level;
    }

    public boolean atLeast(Role other) {
        return this.level >= other.level;
    }
}
