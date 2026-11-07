package com.amber.pad.server;

public final class Native {
    private Native() {}
    public static native boolean grab(int fd, boolean on);
}
