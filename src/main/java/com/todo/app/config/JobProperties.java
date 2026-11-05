package com.todo.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Settings for the background jobs. The schedules themselves are fixed in the @Scheduled annotations;
 * these properties switch each job on or off and tune its thresholds. With more than one instance the
 * jobs should run on one only, otherwise reminders are sent twice.
 */
@ConfigurationProperties(prefix = "app.jobs")
public record JobProperties(@DefaultValue Reminder reminder, @DefaultValue Purge purge) {

    public record Reminder(@DefaultValue("true") boolean enabled,
                           @DefaultValue("24h") Duration window,
                           @DefaultValue("72h") Duration overdueLookback,
                           @DefaultValue("todo@localhost") String from) {
    }

    public record Purge(@DefaultValue("false") boolean enabled,
                        @DefaultValue("30d") Duration retention) {
    }
}
