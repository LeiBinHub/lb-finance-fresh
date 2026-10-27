package com.lb.financefresh.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PushFanout {

    private static final Logger log = LoggerFactory.getLogger(PushFanout.class);
    private final List<Pusher> pushers;

    public PushFanout(List<Pusher> pushers) {
        this.pushers = pushers;
    }

    public List<String> enabled() {
        return pushers.stream().filter(Pusher::enabled).map(Pusher::name).toList();
    }

    /** @return true when nothing is configured, or at least one channel accepted the brief */
    public boolean push(String title, String markdown) {
        List<Pusher> active = pushers.stream().filter(Pusher::enabled).toList();
        if (active.isEmpty()) return true;
        int ok = 0;
        for (Pusher pusher : active) {
            try {
                pusher.push(title, markdown);
                ok++;
                log.info("pushed via {}", pusher.name());
            } catch (Exception e) {
                log.warn("push {} failed: {}", pusher.name(), e.toString());
            }
        }
        return ok > 0;
    }
}
