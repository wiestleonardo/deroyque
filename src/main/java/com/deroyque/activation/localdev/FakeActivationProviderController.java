package com.deroyque.activation.localdev;

import com.deroyque.activation.constants.HeaderConstants;
import com.deroyque.activation.constants.RouteConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RestController
@ConditionalOnProperty(name = "local-dev.enabled", havingValue = "true")
public class FakeActivationProviderController {

    private static final String PRODUCT_CODE_FIELD = "productCode";
    private static final String RETRY_AFTER_SECONDS = "3";
    private static final Long SLOW_DELAY_MILLIS = 5_000L;
    private static final Map<String, String> ACTIVATED_BODY = Map.of("status", "ACTIVATED");

    private final Set<String> receivedKeySet = ConcurrentHashMap.newKeySet();

    private final Set<String> activatedKeySet = ConcurrentHashMap.newKeySet();

    @PostMapping(RouteConstants.ActivationProvider.ACTIVATE)
    public ResponseEntity<Map<String, String>> activate(@RequestHeader(HeaderConstants.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody Map<String, String> body) throws InterruptedException {

        String productCode = body.getOrDefault(PRODUCT_CODE_FIELD, "");

        if (this.activatedKeySet.contains(idempotencyKey)) {

            return ResponseEntity.ok(ACTIVATED_BODY);
        }

        Boolean firstCall = this.receivedKeySet.add(idempotencyKey);

        if (productCode.contains("REJECT")) {

            return ResponseEntity.badRequest().build();
        }

        if (productCode.contains("ERROR")) {

            return ResponseEntity.internalServerError().build();
        }

        if (productCode.contains("BUSY") && firstCall) {

            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS).build();
        }

        this.activatedKeySet.add(idempotencyKey);

        if (productCode.contains("SLOW") && firstCall) {

            Thread.sleep(SLOW_DELAY_MILLIS);
        }

        log.info("Fake provider activated product {} for key {}", productCode, idempotencyKey);

        return ResponseEntity.ok(ACTIVATED_BODY);
    }

}
