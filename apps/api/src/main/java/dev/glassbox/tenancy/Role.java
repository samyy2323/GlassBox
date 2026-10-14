package dev.glassbox.tenancy;

public enum Role {
    VIEWER, EDITOR, OWNER;

    public boolean atLeast(Role min) {
        return ordinal() >= min.ordinal();
    }
}
