package com.deroyque.ativacao.localdev;

import com.deroyque.ativacao.constants.HeaderConstants;
import com.deroyque.ativacao.constants.RotaConstants;
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
@ConditionalOnProperty(name = "local-dev.habilitado", havingValue = "true")
public class ProvedorAtivacaoSimuladoController {

    private static final String CAMPO_PRODUTO = "productCode";
    private static final String RETRY_AFTER_SEGUNDOS = "3";
    private static final Long ATRASO_LENTO_MILISSEGUNDOS = 5_000L;
    private static final Map<String, String> CORPO_ATIVADO = Map.of("status", "ACTIVATED");

    private final Set<String> listaChaveRecebida = ConcurrentHashMap.newKeySet();

    private final Set<String> listaChaveAtivada = ConcurrentHashMap.newKeySet();

    @PostMapping(RotaConstants.ProvedorAtivacao.ATIVAR)
    public ResponseEntity<Map<String, String>> ativar(@RequestHeader(HeaderConstants.CHAVE_IDEMPOTENCIA) String chaveIdempotencia,
            @RequestBody Map<String, String> corpo) throws InterruptedException {

        String codigoProduto = corpo.getOrDefault(CAMPO_PRODUTO, "");

        if (this.listaChaveAtivada.contains(chaveIdempotencia)) {

            return ResponseEntity.ok(CORPO_ATIVADO);
        }

        Boolean primeiraChamada = this.listaChaveRecebida.add(chaveIdempotencia);

        if (codigoProduto.contains("REJECT")) {

            return ResponseEntity.badRequest().build();
        }

        if (codigoProduto.contains("ERROR")) {

            return ResponseEntity.internalServerError().build();
        }

        if (codigoProduto.contains("BUSY") && primeiraChamada) {

            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SEGUNDOS).build();
        }

        this.listaChaveAtivada.add(chaveIdempotencia);

        if (codigoProduto.contains("SLOW") && primeiraChamada) {

            Thread.sleep(ATRASO_LENTO_MILISSEGUNDOS);
        }

        log.info("Provedor simulado ativou o produto {} para a chave {}", codigoProduto, chaveIdempotencia);

        return ResponseEntity.ok(CORPO_ATIVADO);
    }

}
