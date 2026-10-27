package com.lb.financefresh.push;

public interface Pusher {
    String name();

    boolean enabled();

    void push(String title, String markdown) throws Exception;
}
