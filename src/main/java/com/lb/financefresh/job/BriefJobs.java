package com.lb.financefresh.job;

import com.lb.financefresh.model.BriefKind;
import com.lb.financefresh.pipeline.BriefPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class BriefJobs {

    private static final Logger log = LoggerFactory.getLogger(BriefJobs.class);
    private final BriefPipeline pipeline;

    public BriefJobs(BriefPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Scheduled(cron = "${fresh.premarket-cron}", zone = "${fresh.zone}")
    public void premarket() {
        run(BriefKind.PREMARKET);
    }

    @Scheduled(cron = "${fresh.recap-cron}", zone = "${fresh.zone}")
    public void recap() {
        run(BriefKind.RECAP);
    }

    private void run(BriefKind kind) {
        try {
            pipeline.run(kind);
        } catch (Exception e) {
            log.warn("{} skipped: {}", kind, e.getMessage());
        }
    }
}
