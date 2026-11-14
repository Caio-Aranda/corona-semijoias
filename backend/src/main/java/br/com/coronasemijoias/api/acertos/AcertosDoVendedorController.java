package br.com.coronasemijoias.api.acertos;

import br.com.coronasemijoias.api.acertos.ConsultasDeAcertos.FichaDoVendedor;
import br.com.coronasemijoias.api.acertos.ConsultasDeAcertos.ResumoDoVendedor;
import br.com.coronasemijoias.api.acertos.ServicoDeAcertos.PedidoDeInicio;
import br.com.coronasemijoias.api.acertos.ServicoDeAcertos.PedidoDeLeitura;
import br.com.coronasemijoias.api.acertos.ServicoDeAcertos.Quem;
import br.com.coronasemijoias.api.acertos.ServicoDeAcertos.Resultado;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Acerto, lado do vendedor (tarefa 7.4; ADR-0012, item 16). Só o perfil VENDEDOR e só o acerto dele (o vendedor vem do token;
 * 404 para o de outro). O vendedor pede o acerto e bipa; desistir é só da dona. Nunca traz custo.
 */
@RestController
@RequestMapping("/api/vendedor/acertos")
@ExigePerfil("VENDEDOR")
public class AcertosDoVendedorController {

    /** O corpo de "pedir o acerto": só o id do comando, que vem do aplicativo. */
    public record PedidoDeAcerto(UUID id) {
    }

    private final ServicoDeAcertos servico;
    private final ConsultasDeAcertos consultas;

    AcertosDoVendedorController(ServicoDeAcertos servico, ConsultasDeAcertos consultas) {
        this.servico = servico;
        this.consultas = consultas;
    }

    private static Quem quem(UsuarioAutenticado u) {
        return new Quem(u.id(), u.vendedorId(), u.dispositivoId());
    }

    private ResponseEntity<FichaDoVendedor> ficha(Resultado r, UUID acerto, UsuarioAutenticado quem) {
        return ResponseEntity.status(r.jaExistia() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(consultas.fichaDoVendedor(acerto, quem.vendedorId()));
    }

    @Operation(operationId = "pedirMeuAcerto", summary = "Pedir o meu acerto",
            description = "O vendedor pede o próprio acerto: todas as maletas dele passam a \"em acerto\". Precisa de ao menos uma "
                    + "maleta com ele, do computador sem vendas para enviar e de nenhuma maleta conferida em outro computador. "
                    + "Mesmo id devolve 200.")
    @PostMapping
    public ResponseEntity<FichaDoVendedor> pedir(@RequestBody PedidoDeAcerto pedido, @AuthenticationPrincipal UsuarioAutenticado quem) {
        UUID id = pedido == null ? null : pedido.id();
        Resultado r = servico.iniciar(quem(quem), new PedidoDeInicio(id, null, null));
        return ficha(r, r.id(), quem);
    }

    @Operation(operationId = "listarMeusAcertos", summary = "Meus acertos",
            description = "Do mais novo para o mais antigo. Só os do vendedor logado.")
    @GetMapping
    public List<ResumoDoVendedor> listar(@AuthenticationPrincipal UsuarioAutenticado quem) {
        return consultas.doVendedor(quem.vendedorId());
    }

    @Operation(operationId = "verMeuAcerto", summary = "Ver o meu acerto",
            description = "As maletas, a contagem somada por peça e estado e as leituras. 404 para o acerto de outro vendedor.")
    @GetMapping("/{id}")
    public FichaDoVendedor ver(@PathVariable UUID id, @AuthenticationPrincipal UsuarioAutenticado quem) {
        return consultas.fichaDoVendedor(id, quem.vendedorId());
    }

    @Operation(operationId = "registrarMinhaLeituraDoAcerto", summary = "Registrar uma leitura da contagem",
            description = "O vendedor bipa uma peça (ou desfaz) no próprio acerto em andamento. Mesmo id e mesmo conteúdo devolve "
                    + "200; mesmo id com dados diferentes, 409.")
    @PostMapping("/{id}/leituras")
    public ResponseEntity<FichaDoVendedor> ler(@PathVariable UUID id, @RequestBody PedidoDeLeitura pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        return ficha(servico.registrarLeitura(quem(quem), id, pedido), id, quem);
    }
}
