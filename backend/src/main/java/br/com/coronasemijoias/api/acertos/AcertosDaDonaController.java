package br.com.coronasemijoias.api.acertos;

import br.com.coronasemijoias.api.acertos.ConsultasDeAcertos.FichaDaDona;
import br.com.coronasemijoias.api.acertos.ConsultasDeAcertos.Resumo;
import br.com.coronasemijoias.api.acertos.ServicoDeAcertos.PedidoDeDesistencia;
import br.com.coronasemijoias.api.acertos.ServicoDeAcertos.PedidoDeInicio;
import br.com.coronasemijoias.api.acertos.ServicoDeAcertos.PedidoDeLeitura;
import br.com.coronasemijoias.api.acertos.ServicoDeAcertos.Quem;
import br.com.coronasemijoias.api.acertos.ServicoDeAcertos.Resultado;
import br.com.coronasemijoias.api.acertos.ServicoDeFechamento.PedidoDeFechamento;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Acerto, lado da dona (tarefa 7.4; ADR-0012). Só o perfil ADMIN. Os ids dos comandos vêm do aplicativo: 201 na primeira
 * gravação, 200 no reenvio igual, 409 com o mesmo id e dados diferentes.
 */
@RestController
@RequestMapping("/api/dona/acertos")
@ExigePerfil("ADMIN")
public class AcertosDaDonaController {

    private final ServicoDeAcertos servico;
    private final ConsultasDeAcertos consultas;
    private final ServicoDeFechamento fechamento;

    AcertosDaDonaController(ServicoDeAcertos servico, ConsultasDeAcertos consultas, ServicoDeFechamento fechamento) {
        this.fechamento = fechamento;
        this.servico = servico;
        this.consultas = consultas;
    }

    private static Quem quem(UsuarioAutenticado u) {
        return new Quem(u.id(), null, null);
    }

    private ResponseEntity<FichaDaDona> ficha(Resultado r) {
        return ResponseEntity.status(r.jaExistia() ? HttpStatus.OK : HttpStatus.CREATED).body(consultas.fichaDaDona(r.id()));
    }

    @Operation(operationId = "iniciarAcerto", summary = "Iniciar o acerto de um vendedor",
            description = "Liga ao acerto todas as maletas enviadas ou conferidas do vendedor, que passam a \"em acerto\". "
                    + "A semana é a informada; senão a mais recente das maletas; sem maleta, a semana de hoje. Um acerto em "
                    + "andamento por vendedor e um por semana. Mesmo id e mesmo conteúdo devolve 200; mesmo id com dados "
                    + "diferentes, 409.")
    @PostMapping
    public ResponseEntity<FichaDaDona> iniciar(@RequestBody PedidoDeInicio pedido, @AuthenticationPrincipal UsuarioAutenticado quem) {
        return ficha(servico.iniciar(quem(quem), pedido));
    }

    @Operation(operationId = "listarAcertos", summary = "Acertos dos vendedores",
            description = "Do mais novo para o mais antigo. Filtros opcionais: vendedor, semana e situação "
                    + "(EM_ACERTO, ACERTADO ou DESISTIDO).")
    @GetMapping
    public List<Resumo> listar(@RequestParam(required = false) UUID vendedorId, @RequestParam(required = false) UUID semanaId,
            @RequestParam(required = false) String situacao) {
        return consultas.daDona(vendedorId, semanaId, situacao);
    }

    @Operation(operationId = "verAcerto", summary = "Ficha do acerto",
            description = "As maletas do acerto, a contagem somada por peça e estado, as leituras (quem leu, quando e de qual "
                    + "computador), quantas vendas chegaram depois do início e o aviso de envios aguardando do vendedor.")
    @GetMapping("/{id}")
    public FichaDaDona ver(@PathVariable UUID id) {
        return consultas.fichaDaDona(id);
    }

    @Operation(operationId = "registrarLeituraDoAcerto", summary = "Registrar uma leitura da contagem",
            description = "Um bipe (quantidade positiva) ou um desfazer (negativa) de uma peça, boa ou com defeito. A contagem "
                    + "nunca fica negativa. Mesmo id e mesmo conteúdo devolve 200; mesmo id com dados diferentes, 409.")
    @PostMapping("/{id}/leituras")
    public ResponseEntity<FichaDaDona> ler(@PathVariable UUID id, @RequestBody PedidoDeLeitura pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        return ficha(servico.registrarLeitura(quem(quem), id, pedido), id);
    }

    @Operation(operationId = "desistirDoAcerto", summary = "Desistir do acerto",
            description = "Só a dona. Cada maleta volta ao que era antes do acerto e nada de dinheiro é gravado; as leituras "
                    + "ficam como histórico. Motivo obrigatório. Mesmo id devolve 200.")
    @PostMapping("/{id}/desistir")
    public ResponseEntity<FichaDaDona> desistir(@PathVariable UUID id, @RequestBody PedidoDeDesistencia pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        return ficha(servico.desistir(quem(quem), id, pedido), id);
    }

    private ResponseEntity<FichaDaDona> ficha(Resultado r, UUID acerto) {
        return ResponseEntity.status(r.jaExistia() ? HttpStatus.OK : HttpStatus.CREATED).body(consultas.fichaDaDona(acerto));
    }

    @Operation(operationId = "previaDoFechamento", summary = "Prévia do fechamento do acerto",
            description = "Nada é gravado: esperado x contado por peça e maleta, sobras, faltas com valor, defeitos, eventos que vão entrar, "
                    + "devoluções em revisão (impedem), o que não foi aceito, pendências antigas, a comissão por venda, a soma da meta em "
                    + "Guarani, quanto falta para a meta (em Guarani e em Real, pela cotação mais recente), o percentual, os totais e a cotação. "
                    + "Com o acerto em andamento, devolve a prévia (PreviaDoFechamento); com o acerto já fechado, devolve o que foi gravado "
                    + "(Fechamento), que não traz o que falta para a meta. Assume cobrar as faltas no acerto e não aprovar defeito; para "
                    + "outras decisões, use a prévia com decisões.")
    @ApiResponse(responseCode = "200", description = "A prévia (acerto em andamento) ou o fechamento gravado (acerto fechado)",
            content = @Content(schema = @Schema(oneOf = {PreviaDoFechamento.class, ConsultasDeAcertos.Fechamento.class})))
    @GetMapping("/{id}/fechamento")
    public Object previa(@PathVariable UUID id) {
        ConsultasDeAcertos.Fechamento gravado = consultas.fechamento(id);
        return gravado != null ? gravado : fechamento.previa(id, null);
    }

    @Operation(operationId = "previaDoFechamentoComDecisoes", summary = "Prévia do fechamento com as decisões da dona",
            description = "O mesmo cálculo do fechamento com as decisões enviadas (faltas, defeitos, cobranças e pendências antigas) e com o "
                    + "pagamento (recebimentos em dinheiro e Transferência, boleto, o que ficou devendo em parcelas, contas antigas pagas e, "
                    + "a favor do vendedor, crédito ou pagar na hora). Mostra o crédito disponível, o que há a pagar, o que ficou devendo e as contas "
                    + "abertas do vendedor. Nada é gravado.")
    @PostMapping("/{id}/fechamento/previa")
    public PreviaDoFechamento previaComDecisoes(@PathVariable UUID id, @RequestBody PedidoDeFechamento pedido) {
        return fechamento.previa(id, pedido);
    }

    @Operation(operationId = "fecharAcerto", summary = "Fechar o acerto",
            description = "Só a dona. Uma transação só: esperado x contado, faltas, defeitos, eventos, comissão por meta, totais e cotação; "
                    + "as maletas ficam acertadas. Sobra ou devolução em revisão impede (409). As decisões e o líquido mostrado têm de "
                    + "bater com o cálculo de agora (409 com a prévia nova). O pagamento entra na mesma transação: cada recebimento gera a entrada de "
                    + "caixa, o boleto e o que ficou devendo viram contas a receber do vendedor, e o acerto a favor do vendedor vira crédito ou "
                    + "saída de caixa. Mesmo id e mesmas decisões devolve 200.")
    @PostMapping("/{id}/fechar")
    public ResponseEntity<FichaDaDona> fechar(@PathVariable UUID id, @RequestBody PedidoDeFechamento pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        ServicoDeFechamento.Resultado r = fechamento.fechar(quem.id(), id, pedido);
        return ResponseEntity.status(r.jaExistia() ? HttpStatus.OK : HttpStatus.CREATED).body(consultas.fichaDaDona(id));
    }
}
