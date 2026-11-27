package id.pkl.signingpqc.server.account;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Jam sistem; terpisah dari {@link WebConfig} agar tidak membentuk siklus dependensi bean. */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
