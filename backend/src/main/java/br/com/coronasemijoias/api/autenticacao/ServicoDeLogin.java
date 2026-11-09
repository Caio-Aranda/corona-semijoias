package br.com.coronasemijoias.api.autenticacao;

import br.com.coronasemijoias.api.acessos.ControleDeTentativas;
import br.com.coronasemijoias.api.acessos.ControleDeTentativas.Bloqueio;
import br.com.coronasemijoias.api.acessos.ControleDeTentativas.Tentativa;
import br.com.coronasemijoias.api.acessos.PropriedadesDasTentativas;
import br.com.coronasemijoias.api.acessos.ResultadoDaTentativa;
import br.com.coronasemijoias.api.acessos.TravaPorIp;
import br.com.coronasemijoias.api.aplicativo.PropriedadesDoApp;
import br.com.coronasemijoias.api.aplicativo.TipoDeApp;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.seguranca.ConteudoDoToken;
import br.com.coronasemijoias.api.seguranca.EmissorDeToken;
import br.com.coronasemijoias.api.seguranca.EmissorDeToken.TokenEmitido;
import br.com.coronasemijoias.api.sessoes.ServicoDeSessoes;
import br.com.coronasemijoias.api.sessoes.ServicoDeSessoes.SessaoAberta;
import br.com.coronasemijoias.api.sessoes.TipoDeSessao;
import br.com.coronasemijoias.api.usuarios.ConferenciaDeSenha;
import br.com.coronasemijoias.api.usuarios.Logins;
import br.com.coronasemijoias.api.usuarios.Usuario;
import br.com.coronasemijoias.api.usuarios.UsuarioRepositorio;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Login (ADR-0004, itens 1, 2, 4, 6 e 7). Login inexistente, senha errada e usuário inativo com senha
 * errada dão a MESMA resposta ({@code LOGIN_INVALIDO}), para não revelar quais logins existem. Só depois
 * de a senha conferir a API diz que o usuário está desativado, que a senha temporária venceu ou que o
 * aparelho não combina com o perfil.
 *
 * <p>Limite de tentativas (item 7): um pedido por vez de cada IP ({@link TravaPorIp}, com espera
 * máxima); durante o bloqueio nem a senha certa entra (429, sem gastar BCrypt). Toda tentativa é
 * gravada em {@code tentativas_login}. Não há transação por fora: quem espera a vez do IP não segura
 * conexão do banco, e o erro gravado não é desfeito pela exceção da resposta.
 *
 * <p>Sucesso abre a família da sessão (item 2): {@code NORMAL}, com token de renovação; ou
 * {@code TROCA_SENHA}, a do token restrito da senha temporária, sem renovação.
 */
@Service
public class ServicoDeLogin {

    /** O que chega no login: o IP já é o real ({@code IpDoCliente}). */
    public record PedidoDeEntrada(String login, String senha, UUID dispositivoId, String ip, TipoDeApp app) {

        @Override
        public String toString() {
            return "PedidoDeEntrada[login=" + login + ", senha=(oculta), dispositivoId=" + dispositivoId + ", ip=" + ip + ", app=" + app + "]";
        }
    }

    /**
     * O token do login, a família aberta e se o token é o restrito da troca de senha obrigatória
     * (senha temporária certa e dentro do prazo): nesse caso o aplicativo só mostra a tela de criar a
     * senha nova, e não há token de renovação.
     */
    public record ResultadoDoLogin(TokenEmitido token, SessaoAberta sessao, boolean trocaDeSenhaObrigatoria) {
    }

    private final UsuarioRepositorio usuarios;
    private final ConferenciaDeSenha conferencia;
    private final EmissorDeToken emissor;
    private final ServicoDeSessoes sessoes;
    private final ControleDeTentativas tentativas;
    private final TravaPorIp travas;
    private final PropriedadesDasTentativas propriedades;
    private final PropriedadesDoApp propriedadesDoApp;
    private final TransactionOperations transacao;
    private final Clock relogio;

    public ServicoDeLogin(UsuarioRepositorio usuarios, ConferenciaDeSenha conferencia, EmissorDeToken emissor,
            ServicoDeSessoes sessoes, ControleDeTentativas tentativas, TravaPorIp travas,
            PropriedadesDasTentativas propriedades, PropriedadesDoApp propriedadesDoApp,
            TransactionOperations transacao, Clock relogio) {
        this.usuarios = usuarios;
        this.conferencia = conferencia;
        this.emissor = emissor;
        this.sessoes = sessoes;
        this.tentativas = tentativas;
        this.travas = travas;
        this.propriedades = propriedades;
        this.propriedadesDoApp = propriedadesDoApp;
        this.transacao = transacao;
        this.relogio = relogio;
    }

    public ResultadoDoLogin entrar(PedidoDeEntrada pedido) {
        String normalizado = Logins.normalizar(pedido.login());
        Optional<TravaPorIp.Vez> vez = travas.tentar(pedido.ip(), propriedades.esperaDaTrava());
        if (vez.isEmpty()) {
            // Pedidos demais do mesmo IP ao mesmo tempo: recusa curta, sem BCrypt.
            registrar(normalizado, pedido, ResultadoDaTentativa.BLOQUEADO);
            throw ErroDaApi.acessoBloqueado(propriedades.esperaDaTrava());
        }
        try (TravaPorIp.Vez ignorada = vez.get()) {
            return entrarNaVez(normalizado, pedido);
        }
    }

    private ResultadoDoLogin entrarNaVez(String normalizado, PedidoDeEntrada pedido) {
        Instant agora = agora();
        Optional<Bloqueio> bloqueio = tentativas.bloqueioEmVigor(normalizado, pedido.ip(), agora);
        if (bloqueio.isPresent()) {
            // Durante o bloqueio nem a senha certa entra: ela nem é conferida.
            registrar(normalizado, pedido, ResultadoDaTentativa.BLOQUEADO);
            throw ErroDaApi.acessoBloqueado(bloqueio.get().falta(agora));
        }
        // Login que não pode existir (vazio, com espaço no meio ou caractere de controle) nem é
        // procurado, mas passa pela mesma conferência BCrypt (contra o hash fixo): mesma resposta e
        // mesmo tempo da senha errada.
        Optional<Usuario> encontrado = Logins.podeExistir(normalizado)
                ? usuarios.findByLogin(normalizado)
                : Optional.empty();
        String hash = encontrado.map(Usuario::getSenhaHash).orElse(null);
        if (!conferencia.confere(pedido.senha(), hash)) {
            tentativas.registrarErro(normalizado, pedido.ip(), pedido.dispositivoId(), pedido.app(), agora());
            throw ErroDaApi.loginInvalido(pedido.app());
        }
        Usuario usuario = encontrado.orElseThrow();
        if (!usuario.isAtivo()) {
            registrar(normalizado, pedido, ResultadoDaTentativa.USUARIO_DESATIVADO);
            throw new ErroDaApi(HttpStatus.FORBIDDEN, CodigoErro.USUARIO_DESATIVADO);
        }
        // Só depois de a senha conferir (e o usuário estar ativo): não revela que o login existe.
        if (!propriedadesDoApp.aceitaPerfil(pedido.app(), usuario.getPerfil())) {
            registrar(normalizado, pedido, ResultadoDaTentativa.APP_INCOMPATIVEL);
            throw ErroDaApi.appIncompativel(pedido.app());
        }
        if (usuario.senhaTemporariaVencida(relogio.instant())) {
            registrar(normalizado, pedido, ResultadoDaTentativa.SENHA_TEMPORARIA_VENCIDA);
            throw ErroDaApi.senhaTemporariaVencida(pedido.app());
        }
        // Aparelho (item 4): obrigatório no Corona Vendas, ausente no Corona Gestão.
        if ((pedido.app() == TipoDeApp.VENDAS) != (pedido.dispositivoId() != null)) {
            registrar(normalizado, pedido, ResultadoDaTentativa.PEDIDO_INVALIDO);
            throw new ErroDaApi(HttpStatus.BAD_REQUEST, CodigoErro.PEDIDO_INVALIDO);
        }
        return transacao.execute(estado -> sucesso(normalizado, pedido, usuario, hash));
    }

    private ResultadoDoLogin sucesso(String normalizado, PedidoDeEntrada pedido, Usuario usuario, String hash) {
        if (conferencia.precisaRegravar(hash)) {
            usuario.trocarHashDaSenha(conferencia.gerarHash(pedido.senha()));
            usuarios.save(usuario);
        }
        registrar(normalizado, pedido, ResultadoDaTentativa.SUCESSO);
        if (usuario.isTrocaDeSenhaObrigatoria()) {
            // Senha temporária no prazo: só o token restrito, que abre a troca de senha e a saída.
            SessaoAberta sessao = sessoes.abrir(usuario.getId(), TipoDeSessao.TROCA_SENHA, pedido.dispositivoId());
            TokenEmitido token = emissor.emitir(ConteudoDoToken.restritoParaTrocaDeSenha(
                    usuario.getId(), usuario.getPerfil(), usuario.getVendedorId(), sessao.sid(), sessao.dispositivoId()));
            return new ResultadoDoLogin(token, sessao, true);
        }
        SessaoAberta sessao = sessoes.abrir(usuario.getId(), TipoDeSessao.NORMAL, pedido.dispositivoId());
        TokenEmitido token = emissor.emitir(ConteudoDoToken.de(
                usuario.getId(), usuario.getPerfil(), usuario.getVendedorId(), sessao.sid(), sessao.dispositivoId()));
        return new ResultadoDoLogin(token, sessao, false);
    }

    private void registrar(String normalizado, PedidoDeEntrada pedido, ResultadoDaTentativa resultado) {
        tentativas.registrar(new Tentativa(normalizado, pedido.ip(), pedido.dispositivoId(), pedido.app(), resultado, agora()));
    }

    private Instant agora() {
        return relogio.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
