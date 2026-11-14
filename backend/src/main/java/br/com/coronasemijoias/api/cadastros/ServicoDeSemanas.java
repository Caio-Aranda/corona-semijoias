package br.com.coronasemijoias.api.cadastros;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Semanas: número único e datas sem sobreposição (o banco garante as duas coisas, também entre pedidos simultâneos). A semana
 * nasce aberta e só fecha pelo {@link #fechar} (tarefa 7.7; ADR-0012, item 17): depois de fechada ela não muda, não se exclui e
 * nunca reabre (o banco também recusa).
 */
@Service
public class ServicoDeSemanas extends ServicoDeCadastro<Semana> {

    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");

    /** {@code jaExistia}: o fechamento já estava registrado (200); senão, a semana foi fechada agora (201). */
    public record ResultadoDoFechamento(UUID id, boolean jaExistia) {
    }

    private final RepositorioDeSemanas semanas;
    private final JdbcTemplate jdbc;
    private final ServicoDeAuditoria auditoria;
    private final Clock relogio;

    public ServicoDeSemanas(RepositorioDeSemanas semanas, Clock relogio, JdbcTemplate jdbc, ServicoDeAuditoria auditoria) {
        super(semanas, relogio);
        this.semanas = semanas;
        this.jdbc = jdbc;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    @Transactional(readOnly = true)
    public Page<Semana> listar(String busca, Integer pagina, Integer tamanho) {
        return semanas.buscar(padraoDeTexto(busca), pagina(pagina, tamanho));
    }

    @Transactional
    public Semana criar(UUID id, int numero, LocalDate inicio, LocalDate fim, SituacaoDaSemana situacao) {
        conferirDatas(inicio, fim);
        conferirQueNaoFecha(situacao);
        return gravarNovo(new Semana(Validacoes.idDoPedido(id), numero, inicio, fim, SituacaoDaSemana.ABERTA));
    }

    @Transactional
    public Semana editar(UUID id, Integer versao, int numero, LocalDate inicio, LocalDate fim, SituacaoDaSemana situacao) {
        conferirDatas(inicio, fim);
        conferirQueNaoFecha(situacao);
        return editarComVersao(id, Validacoes.versaoDoPedido(versao), semana -> {
            if (semana.getSituacao() == SituacaoDaSemana.FECHADA) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.SEMANA_FECHADA_NAO_MUDA);
            }
            semana.alterar(numero, inicio, fim, SituacaoDaSemana.ABERTA);
        });
    }

    @Override
    @Transactional
    public void excluir(UUID id) {
        if (obter(id).getSituacao() == SituacaoDaSemana.FECHADA) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.SEMANA_FECHADA_NAO_MUDA);
        }
        super.excluir(id);
    }

    /**
     * Fecha a semana (só a dona): só semana aberta que já terminou (o último dia é antes de hoje, em Brasília) e sem acerto em
     * andamento nela. Registra o fechamento (imutável), muda a situação e audita. Nada reabre. O {@code id} é o do comando (do
     * aplicativo): 201 na primeira vez, 200 no reenvio igual, 409 com o mesmo id para outra semana.
     *
     * <p>Trava só a semana ({@code FOR UPDATE}); quem grava com data nela (iniciar acerto, rascunho e envio de maleta, lançamento de
     * caixa) a lê com {@code FOR SHARE} como última trava, então ou entra antes e este fechar vê (e recusa, no caso do acerto), ou
     * o fechar entra antes e o outro vê a semana fechada e é recusado. Não trava mais nada, então nunca espera em volta.
     */
    @Transactional
    public ResultadoDoFechamento fechar(UUID usuarioId, UUID semanaId, UUID comandoId) {
        UUID id = Validacoes.idDoPedido(comandoId);
        List<Map<String, Object>> achada = jdbc.queryForList(
                "SELECT situacao, fim, numero FROM corona.semanas WHERE id = ? AND deleted_at IS NULL FOR UPDATE", semanaId);
        if (achada.isEmpty()) {
            throw naoEncontrado();
        }
        Map<String, Object> semana = achada.get(0);
        List<Map<String, Object>> existente = jdbc.queryForList("SELECT semana_id FROM corona.fechamentos_semana WHERE id = ?", id);
        if (!existente.isEmpty()) {
            if (semanaId.equals(existente.get(0).get("semana_id"))) {
                return new ResultadoDoFechamento(id, true);
            }
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.FECHAMENTO_DA_SEMANA_JA_REGISTRADO_DIFERENTE);
        }
        if ("FECHADA".equals(semana.get("situacao"))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.SEMANA_FECHADA_NAO_MUDA);
        }
        LocalDate fim = ((java.sql.Date) semana.get("fim")).toLocalDate();
        if (!fim.isBefore(LocalDate.ofInstant(relogio.instant(), BRASILIA))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.SEMANA_NAO_TERMINOU);
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM corona.acertos WHERE semana_id = ? AND status = 'EM_ACERTO')", Boolean.class, semanaId))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.SEMANA_COM_ACERTO_EM_ANDAMENTO);
        }
        jdbc.update("INSERT INTO corona.fechamentos_semana (id, semana_id, fechada_em, fechada_por) VALUES (?, ?, ?, ?)", id, semanaId,
                Timestamp.from(relogio.instant()), usuarioId);
        jdbc.update("UPDATE corona.semanas SET situacao = 'FECHADA' WHERE id = ?", semanaId);

        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("numero", semana.get("numero"));
        depois.put("situacao", "FECHADA");
        auditoria.registrar(Ator.usuario(usuarioId), "SEMANA_FECHADA", "semanas", semanaId, Map.of("situacao", "ABERTA"), depois);
        return new ResultadoDoFechamento(id, false);
    }

    private static void conferirDatas(LocalDate inicio, LocalDate fim) {
        if (fim.isBefore(inicio)) {
            throw Validacoes.recusar("fim", "O último dia não pode ser antes do primeiro dia.");
        }
    }

    private static void conferirQueNaoFecha(SituacaoDaSemana situacao) {
        if (situacao == SituacaoDaSemana.FECHADA) {
            throw Validacoes.recusar("situacao", "Para fechar a semana, use Fechar semana.");
        }
    }
}
