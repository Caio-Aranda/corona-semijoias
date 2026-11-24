package br.com.coronasemijoias.api.relatorios;

import static br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.DefinicaoDeTotal.contagem;
import static br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.TODOS;
import static br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.idOpcional;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.Consulta;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Relatórios de Controle da dona (tarefa 8.9; §12, "Controle"): Auditoria, Sincronizações e computadores, Acessos. Só leitura, sem
 * dinheiro, sem migração: leem as tabelas que o servidor já grava ({@code auditoria}, {@code dispositivos}, {@code sync_log},
 * {@code envios_em_quarentena}, {@code tentativas_login}) pelo mesmo motor dos outros relatórios, então a linha, o rodapé e o
 * detalhamento de cada total saem das mesmas condições (§13, item 2). Datas de filtro em UTC, como nos relatórios de cadastro.
 */
@Service
public class ConsultasDeControle {

    private static final Pattern ACAO = Pattern.compile("[A-Z][A-Z_]*");
    private static final Pattern TABELA = Pattern.compile("[a-z][a-z_]*");

    /** Filtros da auditoria. {@code usuarioId} e {@code registroId} são ids (UUID); {@code acao} e {@code tabela} são os códigos. */
    public record FiltrosDaAuditoria(String busca, String usuarioId, String acao, String tabela, String registroId, LocalDate de,
            LocalDate ate, String recorte, Integer pagina, Integer tamanho) {
    }

    public record LinhaDaAuditoria(UUID id, Instant ocorridaEm, UUID usuarioId, String quem, String origem, String acao,
            String oQueFoi, String tabela, String area, UUID registroId, String antes, String depois) {
    }

    public record FiltrosDosComputadores(String busca, String vendedorId, String situacao, String comProblema, LocalDate de,
            LocalDate ate, String recorte, Integer pagina, Integer tamanho) {
    }

    public record LinhaDoComputador(UUID id, UUID vendedorId, String vendedor, String situacao, String situacaoNaTela,
            String versaoApp, Instant primeiroContatoEm, Instant ultimoContatoEm, Instant ultimoRecebimentoEm,
            Instant ultimoEnvioEm, Integer aguardandoEnvio, long enviosComProblema, long emQuarentena) {
    }

    public record FiltrosDosAcessos(String busca, String resultado, String aplicativo, LocalDate de, LocalDate ate, String recorte,
            Integer pagina, Integer tamanho) {
    }

    public record LinhaDoAcesso(UUID id, Instant ocorridaEm, String login, String pessoa, String aplicativo, String resultado,
            String resultadoNaTela, String ip, Instant bloqueadoAte, Instant ipBloqueadoAte) {
    }

    private static final Map<String, String> SITUACOES = Map.of("ATIVO", "Em uso", "AGUARDANDO_LIBERACAO", "Esperando a sua liberação",
            "SUBSTITUIDO", "Trocado por outro", "BLOQUEADO", "Bloqueado");

    private static final Map<String, String> RESULTADOS = Map.of("SUCESSO", "Entrou", "SENHA_INCORRETA", "Senha errada",
            "BLOQUEADO", "Recusada: acesso bloqueado", "USUARIO_DESATIVADO", "Usuário desativado",
            "APP_INCOMPATIVEL", "Programa muito antigo", "SENHA_TEMPORARIA_VENCIDA", "Senha temporária vencida",
            "PEDIDO_INVALIDO", "Computador que não combina com o acesso", "BLOQUEIO_LIMPO", "Bloqueio retirado ao redefinir a senha");

    private final MotorDeRelatorio motor;
    private final JsonMapper json;

    public ConsultasDeControle(MotorDeRelatorio motor, JsonMapper json) {
        this.motor = motor;
        this.json = json;
    }

    // --- auditoria ---

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDaAuditoria> auditoria(FiltrosDaAuditoria f) {
        Consulta c = new Consulta("""
                a.id, a.ocorrida_em, a.ator_usuario_id, u.nome AS quem, a.origem, a.acao, a.tabela, a.registro_id,
                a.antes::text AS antes, a.depois::text AS depois""", """
                corona.auditoria a
                LEFT JOIN corona.usuarios u ON u.id = a.ator_usuario_id""", "a.ocorrida_em DESC, a.id DESC", List.of(
                contagem(TODOS, "Registros", null),
                contagem("por-pessoas", "Feitos por pessoas", "a.origem = 'API'"),
                contagem("pelo-servidor", "Feitos pelo servidor", "a.origem = 'COMANDO'")));
        c.busca(f.busca(), "u.nome", "u.login", "a.acao", "a.tabela", "a.registro_id::text");
        UUID usuario = idOpcional(f.usuarioId(), "usuarioId");
        if (usuario != null) {
            c.onde("a.ator_usuario_id = :usuarioId", "usuarioId", usuario);
        }
        UUID registro = idOpcional(f.registroId(), "registroId");
        if (registro != null) {
            c.onde("a.registro_id = :registroId", "registroId", registro);
        }
        if (preenchido(f.acao())) {
            if (!ACAO.matcher(f.acao().strip()).matches()) {
                throw Validacoes.recusar("acao", "Escolha uma das ações da lista.");
            }
            c.onde("a.acao = :acao", "acao", f.acao().strip());
        }
        if (preenchido(f.tabela())) {
            if (!TABELA.matcher(f.tabela().strip()).matches()) {
                throw Validacoes.recusar("tabela", "Escolha um dos tipos de registro da lista.");
            }
            c.onde("a.tabela = :tabela", "tabela", f.tabela().strip());
        }
        MotorDeRelatorio.conferirPeriodo(f.de(), f.ate());
        c.periodo("a.ocorrida_em", f.de(), f.ate());
        return motor.executar(c, f.recorte(), f.pagina(), f.tamanho(), (rs, n) -> {
            String acao = rs.getString("acao");
            String tabela = rs.getString("tabela");
            String origem = rs.getString("origem");
            return new LinhaDaAuditoria(rs.getObject("id", UUID.class), instante(rs, "ocorrida_em"),
                    rs.getObject("ator_usuario_id", UUID.class),
                    "COMANDO".equals(origem) ? "Pelo servidor" : rs.getString("quem"), origem, acao, TextoDaAuditoria.acao(acao),
                    tabela, TextoDaAuditoria.area(tabela), rs.getObject("registro_id", UUID.class),
                    campos(rs.getString("antes")), campos(rs.getString("depois")));
        });
    }

    @SuppressWarnings("unchecked")
    private String campos(String jsonDosCampos) {
        if (jsonDosCampos == null) {
            return null;
        }
        return TextoDaAuditoria.dosCampos(json.readValue(jsonDosCampos, Map.class));
    }

    // --- sincronizações e computadores ---

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDoComputador> computadores(FiltrosDosComputadores f) {
        Consulta c = new Consulta("""
                c.id, c.vendedor_id, c.vendedor, c.situacao, c.versao_app, c.primeiro_contato_em, c.ultimo_contato_em,
                c.ultimo_pull_em, c.ultimo_push_em, c.aguardando_envio, c.problemas, c.quarentena""", """
                (SELECT d.id, v.id AS vendedor_id, v.nome AS vendedor, d.situacao, d.versao_app, d.primeiro_contato_em,
                        d.ultimo_contato_em, d.ultimo_pull_em, d.ultimo_push_em, d.aguardando_envio,
                        (SELECT count(*) FROM corona.sync_log s
                          WHERE s.dispositivo_id = d.id AND s.resultado IN ('RECUSADO', 'CONFLITO') AND s.resolucao IS NULL) AS problemas,
                        (SELECT count(*) FROM corona.envios_em_quarentena q
                          WHERE q.dispositivo_id = d.id AND q.situacao = 'GUARDADO') AS quarentena
                   FROM corona.dispositivos d
                   JOIN corona.usuarios u ON u.id = d.usuario_id
                   LEFT JOIN corona.vendedores v ON v.id = u.vendedor_id) c""",
                "c.vendedor, c.ultimo_contato_em DESC, c.id", List.of(
                contagem(TODOS, "Computadores", null),
                contagem("em-uso", "Em uso", "c.situacao = 'ATIVO'"),
                contagem("esperando-liberacao", "Esperando a sua liberação", "c.situacao = 'AGUARDANDO_LIBERACAO'"),
                contagem("bloqueados", "Bloqueados", "c.situacao = 'BLOQUEADO'"),
                contagem("com-problema", "Com envio com problema ou guardado à parte",
                        "(c.problemas > 0 OR c.quarentena > 0)")));
        c.busca(f.busca(), "c.vendedor");
        UUID vendedor = idOpcional(f.vendedorId(), "vendedorId");
        if (vendedor != null) {
            c.onde("c.vendedor_id = :vendedorId", "vendedorId", vendedor);
        }
        if (preenchido(f.situacao()) && !"TODAS".equalsIgnoreCase(f.situacao().strip())) {
            String s = f.situacao().strip().toUpperCase();
            if (!SITUACOES.containsKey(s)) {
                throw Validacoes.recusar("situacao", "Escolha uma das situações da lista.");
            }
            c.onde("c.situacao = :situacao", "situacao", s);
        }
        if (preenchido(f.comProblema())) {
            switch (f.comProblema().strip().toUpperCase()) {
                case "SIM" -> c.onde("(c.problemas > 0 OR c.quarentena > 0)");
                case "NAO" -> c.onde("(c.problemas = 0 AND c.quarentena = 0)");
                default -> throw Validacoes.recusar("comProblema", "Escolha sim ou não.");
            }
        }
        MotorDeRelatorio.conferirPeriodo(f.de(), f.ate());
        c.periodo("c.ultimo_contato_em", f.de(), f.ate());
        return motor.executar(c, f.recorte(), f.pagina(), f.tamanho(), (rs, n) -> {
            String situacao = rs.getString("situacao");
            Integer aguardando = (Integer) rs.getObject("aguardando_envio");
            return new LinhaDoComputador(rs.getObject("id", UUID.class), rs.getObject("vendedor_id", UUID.class),
                    rs.getString("vendedor"), situacao, SITUACOES.getOrDefault(situacao, situacao), rs.getString("versao_app"),
                    instante(rs, "primeiro_contato_em"), instante(rs, "ultimo_contato_em"), instante(rs, "ultimo_pull_em"),
                    instante(rs, "ultimo_push_em"), aguardando, rs.getLong("problemas"),
                    rs.getLong("quarentena"));
        });
    }

    // --- acessos ---

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDoAcesso> acessos(FiltrosDosAcessos f) {
        Consulta c = new Consulta("""
                t.id, t.ocorrida_em, t.login, u.nome AS pessoa, t.tipo_app, t.resultado, host(t.ip) AS ip, t.bloqueio_ate,
                t.bloqueio_do_ip_ate""", """
                corona.tentativas_login t
                LEFT JOIN corona.usuarios u ON u.login = t.login""", "t.ocorrida_em DESC, t.id DESC", List.of(
                contagem(TODOS, "Tentativas", null),
                contagem("entradas", "Entradas", "t.resultado = 'SUCESSO'"),
                contagem("erradas", "Senhas erradas", "t.resultado = 'SENHA_INCORRETA'"),
                contagem("bloqueios", "Bloqueios", "(t.bloqueio_ate IS NOT NULL OR t.bloqueio_do_ip_ate IS NOT NULL "
                        + "OR t.resultado = 'BLOQUEADO')")));
        c.busca(f.busca(), "t.login", "u.nome");
        if (preenchido(f.resultado())) {
            String r = f.resultado().strip().toUpperCase();
            if (!RESULTADOS.containsKey(r)) {
                throw Validacoes.recusar("resultado", "Escolha um dos resultados da lista.");
            }
            c.onde("t.resultado = :resultado", "resultado", r);
        }
        if (preenchido(f.aplicativo())) {
            String a = f.aplicativo().strip().toLowerCase();
            if (!a.equals("gestao") && !a.equals("vendas")) {
                throw Validacoes.recusar("aplicativo", "Escolha o Corona Gestão ou o Corona Vendas.");
            }
            c.onde("t.tipo_app = :aplicativo", "aplicativo", a);
        }
        MotorDeRelatorio.conferirPeriodo(f.de(), f.ate());
        c.periodo("t.ocorrida_em", f.de(), f.ate());
        return motor.executar(c, f.recorte(), f.pagina(), f.tamanho(), (rs, n) -> {
            String resultado = rs.getString("resultado");
            String app = rs.getString("tipo_app");
            return new LinhaDoAcesso(rs.getObject("id", UUID.class), instante(rs, "ocorrida_em"), rs.getString("login"),
                    rs.getString("pessoa"), "gestao".equals(app) ? "Corona Gestão" : "vendas".equals(app) ? "Corona Vendas" : null,
                    resultado, RESULTADOS.getOrDefault(resultado, resultado), rs.getString("ip"), instante(rs, "bloqueio_ate"),
                    instante(rs, "bloqueio_do_ip_ate"));
        });
    }

    private static boolean preenchido(String v) {
        return v != null && !v.isBlank();
    }

    private static Instant instante(ResultSet rs, String coluna) throws SQLException {
        OffsetDateTime quando = rs.getObject(coluna, OffsetDateTime.class);
        return quando == null ? null : quando.toInstant();
    }
}
