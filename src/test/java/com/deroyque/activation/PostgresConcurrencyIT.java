package com.deroyque.activation;

import com.deroyque.activation.application.ActivationRequestService;
import com.deroyque.activation.application.CreateActivationCommand;
import com.deroyque.activation.application.CreationResult;
import com.deroyque.activation.domain.ActivationRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mesma corrida de duplicidade, agora contra PostgreSQL real com as migrações Flyway.
 * Sem Docker disponível, o teste é ignorado (não falha o build).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class PostgresConcurrencyIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ActivationRequestService service;

    @Autowired
    private ActivationRequestRepository repository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void concurrentIdenticalRequestsCreateExactlyOneRecord() throws Exception {
        int threads = 20;
        var externalId = "EXT-" + UUID.randomUUID();
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(threads);
        try {
            var futures = new ArrayList<Future<CreationResult>>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return service.create(new CreateActivationCommand("system-a", externalId, "CUST-1",
                            "INTERNET-500", "corr"));
                }));
            }
            start.countDown();

            var results = new ArrayList<CreationResult>();
            for (var future : futures) {
                results.add(future.get());
            }

            assertThat(results).filteredOn(CreationResult::created).hasSize(1);
            assertThat(results).extracting(r -> r.request().getId()).containsOnly(results.getFirst().request().getId());
            assertThat(repository.findAll()).filteredOn(r -> r.getExternalId().equals(externalId)).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
