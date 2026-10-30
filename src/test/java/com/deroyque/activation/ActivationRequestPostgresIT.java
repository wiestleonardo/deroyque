package com.deroyque.activation;

import com.deroyque.activation.dto.CreateActivationRequestDTO;
import com.deroyque.activation.repository.ActivationRequestRepository;
import com.deroyque.activation.service.ActivationRequestService;
import com.deroyque.activation.vo.CreationResultVO;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ActivationRequestPostgresIT {

    private static final String POSTGRESQL_IMAGE = "postgres:16-alpine";
    private static final Integer CONCURRENT_REQUESTS = 20;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>(POSTGRESQL_IMAGE);

    @Autowired
    private ActivationRequestService activationRequestService;

    @Autowired
    private ActivationRequestRepository activationRequestRepository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void shouldCreateOnlyOneRecordWhenIdenticalRequestsArriveTogetherOnPostgres() throws Exception {

        String externalId = "EXT-" + UUID.randomUUID();
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Future<CreationResultVO>> futureList = new ArrayList<>();

        try (ExecutorService executorService = Executors.newFixedThreadPool(CONCURRENT_REQUESTS)) {

            for (int request = 0; request < CONCURRENT_REQUESTS; request++) {

                futureList.add(executorService.submit(() -> {

                    startSignal.await();

                    return this.activationRequestService.create("system-a", "corr", new CreateActivationRequestDTO(externalId, "CUST-1", "INTERNET-500"));
                }));
            }

            startSignal.countDown();

            List<CreationResultVO> resultList = new ArrayList<>();

            for (Future<CreationResultVO> future : futureList) {

                resultList.add(future.get());
            }

            assertThat(resultList).filteredOn(CreationResultVO::created).hasSize(1);
            assertThat(resultList).extracting(result -> result.activationRequestDTO().id())
                    .containsOnly(resultList.getFirst().activationRequestDTO().id());
            assertThat(this.activationRequestRepository.findAll()).filteredOn(activationRequest -> activationRequest.getExternalId().equals(externalId))
                    .hasSize(1);
        }
    }

    @Test
    void shouldListOnlyRequestsOfTheSystemWhenFilteringByStatusOnPostgres() {

        this.activationRequestService.create("system-a", "corr", new CreateActivationRequestDTO("EXT-" + UUID.randomUUID(), "CUST-9", "INTERNET-500"));
        this.activationRequestService.create("system-b", "corr", new CreateActivationRequestDTO("EXT-" + UUID.randomUUID(), "CUST-9", "INTERNET-500"));

        assertThat(this.activationRequestService.list("system-a", "PENDING", "CUST-9", 0, 20).content()).hasSize(1);
        assertThat(this.activationRequestService.list("system-a", null, null, 0, 20).content()).isNotEmpty();
    }

}
