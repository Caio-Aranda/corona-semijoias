package br.com.coronasemijoias.api.sync;

import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.sync.ServicoDeQuarentena.Pedido;
import br.com.coronasemijoias.api.sync.ServicoDeQuarentena.ResultadoGuardado;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A quarentena do computador bloqueado (tarefa 5.8; ADR-0013, item 14). Rota pública na lista fechada: não há token de
 * acesso (a sessão do computador já foi revogada); quem prova ser aquele computador é o token de renovação revogado e o
 * identificador, conferidos pelo {@link ServicoDeQuarentena}.
 */
@RestController
@RequestMapping("/api/vendedor/sync/quarentena")
public class QuarentenaController {

    public record RespostaDaQuarentena(List<ResultadoGuardado> resultados, Instant horaServidor) {
    }

    private final ServicoDeQuarentena quarentena;
    private final Clock relogio;

    QuarentenaController(ServicoDeQuarentena quarentena, Clock relogio) {
        this.quarentena = quarentena;
        this.relogio = relogio;
    }

    @Operation(operationId = "guardarEmQuarentena", summary = "Guardar o que o computador bloqueado ainda não tinha enviado",
            description = "Sem token de acesso. Mande o token de renovação (revogado por bloqueio do computador ou por "
                    + "desativação do vendedor, há no máximo 30 dias), o dispositivoId e até 50 operações (512 KB). O "
                    + "servidor guarda SEM aplicar: GUARDADO, JA_GUARDADO ou RECUSADO por item. Qualquer outro caso "
                    + "responde 401 SESSAO_TERMINADA, sem dizer o motivo. Resposta definitiva: o app pode apagar os dados.")
    @PostMapping
    public ResponseEntity<RespostaDaQuarentena> guardar(@RequestBody(required = false) Pedido pedido,
            HttpServletRequest requisicao) {
        if (requisicao.getContentLengthLong() > ServicoDeEnvio.MAXIMO_DE_BYTES) {
            throw new ErroDaApi(HttpStatus.CONTENT_TOO_LARGE, CodigoErro.LOTE_GRANDE_DEMAIS);
        }
        List<ResultadoGuardado> resultados = quarentena.guardar(pedido);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new RespostaDaQuarentena(resultados, relogio.instant().truncatedTo(ChronoUnit.MILLIS)));
    }
}
