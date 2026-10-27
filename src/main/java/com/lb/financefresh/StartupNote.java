package com.lb.financefresh;

import com.lb.financefresh.config.FreshSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
class StartupNote implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupNote.class);
    private final FreshSettings settings;

    StartupNote(FreshSettings settings) {
        this.settings = settings;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (settings.requiredMissing().isEmpty()) {
            log.info("finance-fresh ready, watchlist={}", settings.watchlist());
        } else {
            log.info("finance-fresh waiting for {}", settings.requiredMissing());
        }
    }
}
