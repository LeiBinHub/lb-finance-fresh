package com.lb.financefresh.job;

import com.lb.financefresh.market.TreeNews;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TreeNewsJob {

    private final TreeNews treeNews;

    public TreeNewsJob(TreeNews treeNews) {
        this.treeNews = treeNews;
    }

    @Scheduled(fixedDelayString = "${fresh.tree-news-ms:2000}", initialDelay = 1500)
    public void listen() {
        treeNews.pull();
        treeNews.fillChinese();
    }
}
