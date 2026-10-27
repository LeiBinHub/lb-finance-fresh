package com.lb.financefresh.pipeline;

import com.lb.financefresh.model.SourceLink;
import org.springframework.stereotype.Component;

import java.util.List;

/** Links collected for the brief that is running. The pipeline lock keeps one run at a time. */
@Component
public class RunSources {

    private List<SourceLink> items = List.of();

    public void clear() {
        items = List.of();
    }

    public void set(List<SourceLink> items) {
        this.items = List.copyOf(items);
    }

    public List<SourceLink> list() {
        return items;
    }
}
