package br.com.coronasemijoias.api.relatorios;

import br.com.coronasemijoias.api.relatorios.ConsultasDeControle.FiltrosDaAuditoria;
import br.com.coronasemijoias.api.relatorios.ConsultasDeControle.FiltrosDosAcessos;
import br.com.coronasemijoias.api.relatorios.ConsultasDeControle.FiltrosDosComputadores;
import br.com.coronasemijoias.api.relatorios.ConsultasDeControle.LinhaDaAuditoria;
import br.com.coronasemijoias.api.relatorios.ConsultasDeControle.LinhaDoAcesso;
import br.com.coronasemijoias.api.relatorios.ConsultasDeControle.LinhaDoComputador;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import io.swagger.v3.oas.annotations.Operation;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Relatórios de Controle da dona (tarefa 8.9, §12). Só ADMIN, só leitura. Mesma forma dos outros relatórios: linhas paginadas,
 * rodapé e detalhamento de cada total (parâmetro {@code recorte} = a chave do total). Nenhum dado de custo; nunca senha, hash nem token.
 */
@RestController
@RequestMapping("/api/dona/relatorios/controle")
@ExigePerfil("ADMIN")
public class RelatoriosDeControleController {

    private static final String PADRAO = " Datas em UTC (de e ate inclusive). A página começa em 0 (até 100 por página). "
            + "recorte = a chave de um total do rodapé: devolve só as linhas que formam esse total.";

    private final ConsultasDeControle consultas;

    public RelatoriosDeControleController(ConsultasDeControle consultas) {
        this.consultas = consultas;
    }

    @Operation(operationId = "relatorioDeAuditoria", summary = "Auditoria: quem fez o quê",
            description = "Cada ação registrada (preço, estoque, desconto, cancelamento, reembolso, autorização de exceção, restrição e as "
                    + "outras), da mais nova para a mais antiga: quem, quando, o quê, em qual registro e o antes e o depois em palavras da "
                    + "loja (nunca senha, hash nem token). Ação do servidor aparece como \"Pelo servidor\". Filtros: busca (pessoa, ação ou "
                    + "registro), usuarioId, acao (código, ex. PRECO_VENDA_ALTERADO), tabela (tipo de registro, ex. produtos), registroId, "
                    + "de e ate." + PADRAO)
    @GetMapping("/auditoria")
    public RelatorioDeCadastro<LinhaDaAuditoria> auditoria(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String usuarioId, @RequestParam(required = false) String acao,
            @RequestParam(required = false) String tabela, @RequestParam(required = false) String registroId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.auditoria(new FiltrosDaAuditoria(busca, usuarioId, acao, tabela, registroId, de, ate, recorte, pagina, tamanho));
    }

    @Operation(operationId = "relatorioDeComputadores", summary = "Sincronizações e computadores",
            description = "Um computador por linha: de qual vendedor, em que situação, a versão, o último recebimento e o último envio, quantas "
                    + "operações aguardam envio, quantos envios têm problema sem decisão e quantos estão guardados à parte esperando a dona. "
                    + "Filtros: busca (nome do vendedor), vendedorId, situacao (ATIVO, AGUARDANDO_LIBERACAO, SUBSTITUIDO, BLOQUEADO ou TODAS), "
                    + "comProblema (SIM ou NAO), de e ate (sobre o último contato)." + PADRAO)
    @GetMapping("/computadores")
    public RelatorioDeCadastro<LinhaDoComputador> computadores(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String vendedorId, @RequestParam(required = false) String situacao,
            @RequestParam(required = false) String comProblema,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.computadores(new FiltrosDosComputadores(busca, vendedorId, situacao, comProblema, de, ate, recorte, pagina, tamanho));
    }

    @Operation(operationId = "relatorioDeAcessos", summary = "Acessos: entradas, erros e bloqueios",
            description = "Cada tentativa de entrar, da mais nova para a mais antiga: quem, em qual programa, o resultado em palavras simples, "
                    + "o endereço de internet e, quando a tentativa completou o limite de erros, até quando o acesso ficou bloqueado. Login que "
                    + "não existe aparece sem nome de pessoa. Filtros: busca (login ou nome), resultado (SUCESSO, SENHA_INCORRETA, BLOQUEADO, "
                    + "USUARIO_DESATIVADO, APP_INCOMPATIVEL, SENHA_TEMPORARIA_VENCIDA, PEDIDO_INVALIDO ou BLOQUEIO_LIMPO), aplicativo (gestao ou "
                    + "vendas), de e ate." + PADRAO)
    @GetMapping("/acessos")
    public RelatorioDeCadastro<LinhaDoAcesso> acessos(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String resultado, @RequestParam(required = false) String aplicativo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String recorte, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return consultas.acessos(new FiltrosDosAcessos(busca, resultado, aplicativo, de, ate, recorte, pagina, tamanho));
    }
}
