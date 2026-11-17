package br.com.coronasemijoias.api.cadastros;

import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServicoDeRestricoes extends ServicoDeCadastroAtivavel<Restricao> {

    private final RepositorioDeRestricoes restricoes;
    private final JdbcTemplate jdbc;
    private final ServicoDeAuditoria auditoria;

    public ServicoDeRestricoes(RepositorioDeRestricoes restricoes, Clock relogio, JdbcTemplate jdbc,
            ServicoDeAuditoria auditoria) {
        super(restricoes, relogio, auditoria, "restricoes");
        this.restricoes = restricoes;
        this.jdbc = jdbc;
        this.auditoria = auditoria;
    }

    @Transactional(readOnly = true)
    public Page<Restricao> listar(String busca, String situacao, Integer pagina, Integer tamanho) {
        return restricoes.buscar(padraoDeTexto(busca), Situacao.ler(situacao).valores(), pagina(pagina, tamanho));
    }

    @Transactional
    public Restricao criar(String codigo, String descricao, boolean bloqueiaRemessa) {
        return gravarNovo(new Restricao(codigo.strip(), descricao.strip(), bloqueiaRemessa));
    }

    /** Edita a restrição; o que mudou (código, descrição ou o que ela bloqueia) fica na auditoria (§11), na mesma transação. */
    @Transactional
    public Restricao editar(UUID id, Integer versao, String codigo, String descricao, boolean bloqueiaRemessa, UUID quem) {
        Restricao atual = obter(id);
        Map<String, Object> antes = campos(atual.getCodigo(), atual.getDescricao(), atual.isBloqueiaRemessa());
        Map<String, Object> depois = campos(codigo.strip(), descricao.strip(), bloqueiaRemessa);
        Restricao editada = editarComVersao(id, Validacoes.versaoDoPedido(versao),
                restricao -> restricao.alterar(codigo.strip(), descricao.strip(), bloqueiaRemessa));
        if (!antes.equals(depois)) {
            auditoria.registrar(Ator.usuario(quem), "RESTRICAO_ALTERADA", "restricoes", id, antes, depois);
        }
        return editada;
    }

    private static Map<String, Object> campos(String codigo, String descricao, boolean bloqueiaRemessa) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("codigo", codigo);
        m.put("descricao", descricao);
        m.put("bloqueia_remessa", bloqueiaRemessa);
        return m;
    }

    /** Quem está no cadastro de algum vendedor não sai: a dona troca antes (glossário, parte 6). */
    @Override
    @Transactional
    public void excluir(UUID id) {
        obter(id);
        Boolean emUso = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM corona.vendedores WHERE restricao_id = ? AND deleted_at IS NULL)", Boolean.class, id);
        if (Boolean.TRUE.equals(emUso)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CADASTRO_EM_USO);
        }
        super.excluir(id);
    }
}
