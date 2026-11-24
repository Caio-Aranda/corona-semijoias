package br.com.coronasemijoias.api.remessas;

import br.com.coronasemijoias.api.cadastros.Pagina;
import br.com.coronasemijoias.api.fotos.FotosDosProdutosController.FotoPrincipal;
import br.com.coronasemijoias.api.remessas.ConsultasDeRemessas.Detalhe;
import br.com.coronasemijoias.api.remessas.ConsultasDeRemessas.Filtro;
import br.com.coronasemijoias.api.remessas.ConsultasDeRemessas.Item;
import br.com.coronasemijoias.api.remessas.ConsultasDeRemessas.Produto;
import br.com.coronasemijoias.api.remessas.ConsultasDeRemessas.Resumo;
import br.com.coronasemijoias.api.remessas.ServicoDeRemessas.PedidoDeRascunho;
import br.com.coronasemijoias.api.remessas.ServicoDeRemessas.Resultado;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import io.swagger.v3.oas.annotations.Operation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Remessas (maletas) da dona (tarefa 3.2; ADR-0010). Só ADMIN: o vendedor não monta nem altera remessa. O servidor
 * calcula totais e congela o preço no envio; o aplicativo manda só produto e quantidade. Nenhuma resposta traz custo.
 */
@RestController
@RequestMapping("/api/dona/remessas")
@ExigePerfil("ADMIN")
public class RemessasDaDonaController {

    public record PedidoDoRascunho(UUID id, UUID vendedorId, UUID semanaId, UUID pracaId, String tipo) {
    }

    public record PedidoDoItem(Integer quantidade, Integer version) {
    }

    public record RespostaDaPeca(UUID id, String nome, String referencia, String codigo, FotoPrincipal fotoPrincipal) {

        static RespostaDaPeca de(Produto p) {
            return new RespostaDaPeca(p.id(), p.nome(), p.referencia(), p.codigo(), FotoPrincipal.de(p.fotoPrincipalHash()));
        }
    }

    public record RespostaDoItem(UUID id, RespostaDaPeca produto, int quantidade, BigDecimal precoUnitario, BigDecimal total,
            boolean precoCongelado) {

        static RespostaDoItem de(Item i) {
            return new RespostaDoItem(i.id(), RespostaDaPeca.de(i.produto()), i.quantidade(), i.precoUnitario(), i.total(),
                    i.precoCongelado());
        }
    }

    public record RespostaDoResumo(UUID id, String codigo, int version, UUID vendedorId, String vendedorNome, UUID semanaId, int semanaNumero,
            UUID pracaId, String pracaNome, String tipo, String status, int totalPecas, BigDecimal totalValor,
            Instant criadaEm, Instant enviadaEm) {

        static RespostaDoResumo de(Resumo r) {
            return new RespostaDoResumo(r.id(), r.codigo(), r.version(), r.vendedorId(), r.vendedorNome(), r.semanaId(), r.semanaNumero(),
                    r.pracaId(), r.pracaNome(), r.tipo(), r.status(), r.totalPecas(), r.totalValor(), r.criadaEm(), r.enviadaEm());
        }
    }

    public record RespostaDaRemessa(UUID id, String codigo, int version, UUID vendedorId, String vendedorNome, UUID semanaId,
            int semanaNumero, UUID pracaId, String pracaNome, String tipo, String status, int totalPecas,
            BigDecimal totalValor, Instant criadaEm, Instant enviadaEm, List<RespostaDoItem> itens, List<String> avisos) {

        static RespostaDaRemessa de(Detalhe d) {
            Resumo r = d.resumo();
            return new RespostaDaRemessa(r.id(), r.codigo(), r.version(), r.vendedorId(), r.vendedorNome(), r.semanaId(), r.semanaNumero(),
                    r.pracaId(), r.pracaNome(), r.tipo(), r.status(), r.totalPecas(), r.totalValor(), r.criadaEm(),
                    r.enviadaEm(), d.itens().stream().map(RespostaDoItem::de).toList(), d.avisos());
        }
    }

    private final ServicoDeRemessas servico;
    private final ConsultasDeRemessas consultas;

    public RemessasDaDonaController(ServicoDeRemessas servico, ConsultasDeRemessas consultas) {
        this.servico = servico;
        this.consultas = consultas;
    }

    @Operation(operationId = "criarRemessa", summary = "Começar uma maleta (rascunho)",
            description = "Cria o rascunho: vendedor, semana, praça (se vazia, a do vendedor) e tipo NOVA ou REPOSICAO. Rascunho não "
                    + "mexe no estoque. Recusa (409) vendedor inativo ou com restrição que impede montar maleta. O id (UUID) vem do "
                    + "aplicativo: mesmo id e mesmos dados devolve o rascunho (200); primeira vez 201; outros dados, 409.")
    @PostMapping
    public ResponseEntity<RespostaDaRemessa> criar(@RequestBody PedidoDoRascunho pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        return resposta(servico.criarRascunho(new PedidoDeRascunho(pedido.id(), pedido.vendedorId(), pedido.semanaId(),
                pedido.pracaId(), pedido.tipo()), quem.id()));
    }

    @Operation(operationId = "colocarItemNaRemessa", summary = "Colocar uma peça no rascunho",
            description = "Põe a peça (produto e quantidade) ou troca a quantidade dela. O app manda só produto e quantidade; o "
                    + "servidor calcula preço e totais. Exige a version lida (409 se outra pessoa mudou antes). Só rascunho.")
    @PutMapping("/{id}/itens/{produtoId}")
    public RespostaDaRemessa colocarItem(@PathVariable UUID id, @PathVariable UUID produtoId, @RequestBody PedidoDoItem pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        servico.colocarItem(id, produtoId, pedido.quantidade(), pedido.version(), quem.id());
        return RespostaDaRemessa.de(consultas.ver(id));
    }

    @Operation(operationId = "tirarItemDaRemessa", summary = "Tirar uma peça do rascunho", description = "Exige a version lida. Só rascunho.")
    @DeleteMapping("/{id}/itens/{produtoId}")
    public RespostaDaRemessa tirarItem(@PathVariable UUID id, @PathVariable UUID produtoId, @RequestParam Integer version,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        servico.tirarItem(id, produtoId, version, quem.id());
        return RespostaDaRemessa.de(consultas.ver(id));
    }

    @Operation(operationId = "excluirRascunhoDeRemessa", summary = "Excluir o rascunho", description = "Exclusão lógica; exige a version lida. Só rascunho.")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void excluir(@PathVariable UUID id, @RequestParam Integer version, @AuthenticationPrincipal UsuarioAutenticado quem) {
        servico.excluirRascunho(id, version, quem.id());
    }

    @Operation(operationId = "enviarRemessa", summary = "Enviar a maleta",
            description = "RASCUNHO para ENVIADA, numa transação só: confere vendedor (ativo, sem restrição que impede), peças e "
                    + "limite de consignação (valor em aberto a preço de venda mais esta remessa; vendedor sem limite não é "
                    + "bloqueado), congela o preço de venda de hoje e baixa o estoque central (tudo ou nada; falta de peça recusa "
                    + "a remessa inteira dizendo qual). Enviar de novo uma remessa já enviada devolve 200 sem baixar o estoque.")
    @PostMapping("/{id}/enviar")
    public ResponseEntity<RespostaDaRemessa> enviar(@PathVariable UUID id, @AuthenticationPrincipal UsuarioAutenticado quem) {
        return resposta(servico.enviar(id, quem.id()));
    }

    @Operation(operationId = "verRemessa", summary = "Ver uma remessa", description = "Com os itens, fotos, preços e totais calculados pelo servidor, e avisos.")
    @GetMapping("/{id}")
    public RespostaDaRemessa ver(@PathVariable UUID id) {
        return RespostaDaRemessa.de(consultas.ver(id));
    }

    @Operation(operationId = "listarRemessas", summary = "Lista de remessas",
            description = "Filtros: vendedorId, semanaId e status (RASCUNHO, ENVIADA, CONFERIDA, EM_ACERTO, ACERTADA, CANCELADA). "
                    + "Mais nova primeiro. A página começa em 0 (até 200 por página).")
    @GetMapping
    public Pagina<RespostaDoResumo> listar(@RequestParam(required = false) UUID vendedorId,
            @RequestParam(required = false) UUID semanaId, @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer pagina, @RequestParam(required = false) Integer tamanho) {
        return Pagina.de(consultas.listar(new Filtro(vendedorId, semanaId, status), pagina, tamanho), RespostaDoResumo::de);
    }

    private ResponseEntity<RespostaDaRemessa> resposta(Resultado r) {
        return ResponseEntity.status(r.jaExistia() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(RespostaDaRemessa.de(consultas.ver(r.remessaId())));
    }
}
