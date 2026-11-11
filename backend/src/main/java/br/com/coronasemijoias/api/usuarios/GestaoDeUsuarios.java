package br.com.coronasemijoias.api.usuarios;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.cadastros.ServicoDeCadastro;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.sessoes.MotivoDaRevogacao;
import br.com.coronasemijoias.api.sessoes.ServicoDeSessoes;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Listar, ver, editar, desativar e reativar usuários, só pela dona. Desativar encerra na hora todas as
 * sessões do usuário. Toda mudança entra na auditoria na mesma transação.
 *
 * <p>Regras: a dona não desativa nem rebaixa a si mesma e o sistema nunca fica sem administradora ativa.
 * As mudanças que mexem em administradoras pegam uma trava do banco antes de contar, para que duas
 * donas que se desativam ao mesmo tempo não deixem o sistema sem ninguém.
 */
@Service
public class GestaoDeUsuarios {

    private static final Logger LOG = LoggerFactory.getLogger(GestaoDeUsuarios.class);

    /** Chave da trava (advisory lock) que ordena as mudanças que mexem em administradoras. */
    private static final String TRAVA_DAS_ADMINISTRADORAS = "corona.usuarios.administradoras";

    private final UsuarioRepositorio usuarios;
    private final ServicoDeUsuarios servicoDeUsuarios;
    private final ServicoDeSessoes sessoes;
    private final ServicoDeAuditoria auditoria;
    private final JdbcTemplate jdbc;

    public GestaoDeUsuarios(UsuarioRepositorio usuarios, ServicoDeUsuarios servicoDeUsuarios,
            ServicoDeSessoes sessoes, ServicoDeAuditoria auditoria, JdbcTemplate jdbc) {
        this.usuarios = usuarios;
        this.servicoDeUsuarios = servicoDeUsuarios;
        this.sessoes = sessoes;
        this.auditoria = auditoria;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Page<Usuario> listar(String busca, String perfil, Boolean ativo, Integer pagina, Integer tamanho) {
        String limpo = busca == null ? "" : busca.strip().replaceAll("[%_\\\\]", "").toLowerCase(Locale.ROOT);
        List<Boolean> situacoes = ativo == null ? List.of(true, false) : List.of(ativo);
        String filtroDePerfil = perfil == null ? "" : perfil.strip().toUpperCase(Locale.ROOT);
        int p = pagina == null ? 0 : pagina;
        int t = tamanho == null ? ServicoDeCadastro.TAMANHO_PADRAO : tamanho;
        if (p < 0) {
            throw Validacoes.recusar("pagina", "A página não pode ser negativa.");
        }
        if (t < 1 || t > ServicoDeCadastro.TAMANHO_MAXIMO) {
            throw Validacoes.recusar("tamanho", "Escolha de 1 a " + ServicoDeCadastro.TAMANHO_MAXIMO + " itens por página.");
        }
        return usuarios.buscar("%" + limpo + "%", filtroDePerfil, situacoes, PageRequest.of(p, t));
    }

    @Transactional(readOnly = true)
    public Usuario obter(UUID id) {
        return usuarios.findById(id).orElseThrow(GestaoDeUsuarios::naoEncontrado);
    }

    @Transactional
    public Usuario editar(UUID id, UUID quem, int versaoLida, String nome, String perfil, UUID vendedorId) {
        travarAdministradoras();
        Usuario usuario = obter(id);
        if (!Objects.equals(usuario.getVersion(), versaoLida)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CADASTRO_ALTERADO);
        }
        if (!servicoDeUsuarios.perfilExiste(perfil)) {
            throw ServicoDeUsuarios.dadosInvalidos("perfil");
        }
        if (ServicoDeUsuarios.PERFIL_ADMIN.equals(perfil) && vendedorId != null) {
            throw ServicoDeUsuarios.dadosInvalidos("vendedorId");
        }
        servicoDeUsuarios.conferirVendedor(perfil, vendedorId);
        boolean mudouOPerfil = !usuario.getPerfil().equals(perfil);
        boolean mudouOAcesso = mudouOPerfil || !Objects.equals(usuario.getVendedorId(), vendedorId);
        if (mudouOPerfil) {
            if (id.equals(quem)) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.NAO_MUDAR_O_PROPRIO_ACESSO);
            }
            if (usuario.isAtivo() && ServicoDeUsuarios.PERFIL_ADMIN.equals(usuario.getPerfil())) {
                exigirOutraAdministradoraAtiva(id);
            }
        }
        Map<String, Object> antes = ServicoDeUsuarios.instantaneo(usuario);
        usuario.editar(nome.strip(), perfil, vendedorId);
        usuarios.saveAndFlush(usuario);
        if (mudouOAcesso) {
            // O token antigo leva o tipo de acesso e o vendedor de antes: a pessoa entra de novo.
            sessoes.revogarTodasDoUsuario(id, MotivoDaRevogacao.ACESSO_ALTERADO);
        }
        auditoria.registrar(Ator.usuario(quem), "USUARIO_EDITADO", "usuarios", id, antes,
                ServicoDeUsuarios.instantaneo(usuario));
        LOG.info("Usuário editado: login {}, perfil {}", usuario.getLogin(), usuario.getPerfil());
        return usuario;
    }

    /** Desativa e encerra TODAS as sessões do usuário na mesma transação. Desativar quem já está desativado não muda nada. */
    @Transactional
    public Usuario desativar(UUID id, UUID quem) {
        travarAdministradoras();
        Usuario usuario = obter(id);
        if (id.equals(quem)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.NAO_DESATIVAR_A_SI_MESMA);
        }
        if (!usuario.isAtivo()) {
            return usuario;
        }
        if (ServicoDeUsuarios.PERFIL_ADMIN.equals(usuario.getPerfil())) {
            exigirOutraAdministradoraAtiva(id);
        }
        Map<String, Object> antes = ServicoDeUsuarios.instantaneo(usuario);
        usuario.definirAtivo(false);
        usuarios.saveAndFlush(usuario);
        sessoes.revogarTodasDoUsuario(id, MotivoDaRevogacao.USUARIO_DESATIVADO);
        auditoria.registrar(Ator.usuario(quem), "USUARIO_DESATIVADO", "usuarios", id, antes,
                ServicoDeUsuarios.instantaneo(usuario));
        LOG.info("Usuário desativado: login {}", usuario.getLogin());
        return usuario;
    }

    /**
     * Desativa os usuários ligados a um vendedor (a dona inativou o cadastro dele): mesma regra de {@link #desativar},
     * com sessões encerradas e auditoria de cada um. Quem já está desativado fica como está.
     */
    @Transactional
    public void desativarDoVendedor(UUID vendedorId, UUID quem) {
        List<UUID> ids = jdbc.queryForList("SELECT id FROM corona.usuarios WHERE vendedor_id = ? AND ativo ORDER BY id",
                UUID.class, vendedorId);
        for (UUID id : ids) {
            desativar(id, quem);
        }
    }

    /** Reativa. As sessões antigas continuam encerradas: a pessoa entra de novo com a senha dela. */
    @Transactional
    public Usuario reativar(UUID id, UUID quem) {
        Usuario usuario = obter(id);
        if (usuario.isAtivo()) {
            return usuario;
        }
        Map<String, Object> antes = ServicoDeUsuarios.instantaneo(usuario);
        usuario.definirAtivo(true);
        usuarios.saveAndFlush(usuario);
        auditoria.registrar(Ator.usuario(quem), "USUARIO_REATIVADO", "usuarios", id, antes,
                ServicoDeUsuarios.instantaneo(usuario));
        LOG.info("Usuário reativado: login {}", usuario.getLogin());
        return usuario;
    }

    /** Uma mudança por vez entre as que contam administradoras (a trava solta no fim da transação). */
    private void travarAdministradoras() {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))::text", String.class, TRAVA_DAS_ADMINISTRADORAS);
    }

    private void exigirOutraAdministradoraAtiva(UUID id) {
        Integer outras = jdbc.queryForObject(
                "SELECT count(*) FROM corona.usuarios WHERE perfil = 'ADMIN' AND ativo AND id <> ?", Integer.class, id);
        if (outras == null || outras == 0) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.ULTIMA_ADMINISTRADORA);
        }
    }

    private static ErroDaApi naoEncontrado() {
        return new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.USUARIO_NAO_ENCONTRADO);
    }
}
