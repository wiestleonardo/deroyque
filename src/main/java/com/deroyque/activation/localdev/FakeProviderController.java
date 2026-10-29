package com.deroyque.activation.localdev;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provedor externo simulado, para rodar a aplicação sem Docker. O comportamento depende do productCode:
 * <ul>
 *     <li>contém REJECT → 400 (falha definitiva)</li>
 *     <li>contém ERROR → 500 sempre (esgota as tentativas)</li>
 *     <li>contém SLOW → na 1ª chamada demora mais que o read-timeout; depois responde 200</li>
 *     <li>contém BUSY → na 1ª chamada 503 com Retry-After: 3; depois 200</li>
 *     <li>qualquer outro → 200</li>
 * </ul>
 * Respeita a Idempotency-Key: uma chave já ativada sempre devolve 200 sem "ativar de novo".
 */
@RestController
@ConditionalOnProperty(name = "app.local-dev.enabled", havingValue = "true")
public class FakeProviderController {

    private static final Logger log = LoggerFactory.getLogger(FakeProviderController.class);

    private final Set<String> seenKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> activatedKeys = ConcurrentHashMap.newKeySet();

    @PostMapping("/provider/v1/activate")
    public ResponseEntity<Map<String, String>> activate(@RequestHeader("Idempotency-Key") String key,
                                                        @RequestBody Map<String, String> body)
            throws InterruptedException {
        var productCode = body.getOrDefault("productCode", "");
        if (activatedKeys.contains(key)) {
            log.info("[fake-provider] chave {} já ativada; resposta idempotente", key);
            return ResponseEntity.ok(Map.of("status", "ACTIVATED"));
        }
        boolean firstCall = seenKeys.add(key);

        if (productCode.contains("REJECT")) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid product"));
        }
        if (productCode.contains("ERROR")) {
            return ResponseEntity.internalServerError().build();
        }
        if (productCode.contains("BUSY") && firstCall) {
            return ResponseEntity.status(503).header(HttpHeaders.RETRY_AFTER, "3").build();
        }
        if (productCode.contains("SLOW") && firstCall) {
            activatedKeys.add(key); // ativou, mas o cliente vai desistir antes da resposta
            Thread.sleep(5_000);
        }
        activatedKeys.add(key);
        log.info("[fake-provider] produto {} ativado para chave {}", productCode, key);
        return ResponseEntity.ok(Map.of("status", "ACTIVATED"));
    }
}
