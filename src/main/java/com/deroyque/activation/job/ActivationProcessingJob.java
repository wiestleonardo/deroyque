package com.deroyque.activation.job;

import com.deroyque.activation.service.ActivationProcessingService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "processing.job.enabled", havingValue = "true")
public class ActivationProcessingJob {

    private final ActivationProcessingService activationProcessingService;

    @Scheduled(fixedDelayString = "${processing.job.interval}")
    public void execute() {

        activationProcessingService.processPending();
    }

}
