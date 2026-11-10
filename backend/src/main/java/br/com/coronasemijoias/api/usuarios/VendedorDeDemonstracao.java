package br.com.coronasemijoias.api.usuarios;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cria um usuário VENDEDOR de teste ao subir, <strong>somente no perfil {@code dev}</strong>, para quem
 * desenvolve entrar no Corona Vendas sem pedir a ninguém. A senha vem do {@code .env}
 * ({@code DEMO_VENDEDOR_SENHA}), nunca do código. Sem ela (ou com "troque-isto"), não cria nada. Se o
 * login já existe, não mexe. Não liga o usuário a um cadastro de vendedor (a tabela é da Fase 1).
 */
@Component
@Profile("dev")
public class VendedorDeDemonstracao implements SmartInitializingSingleton {

    private static final Logger LOG = LoggerFactory.getLogger(VendedorDeDemonstracao.class);
    private static final String VALOR_DE_EXEMPLO = "troque-isto";

    private final PropriedadesDoVendedorDeDemonstracao dados;
    private final UsuarioRepositorio usuarios;
    private final ConferenciaDeSenha conferencia;
    private final TransactionTemplate transacao;

    public VendedorDeDemonstracao(PropriedadesDoVendedorDeDemonstracao dados, UsuarioRepositorio usuarios,
            ConferenciaDeSenha conferencia, TransactionTemplate transacao) {
        this.dados = dados;
        this.usuarios = usuarios;
        this.conferencia = conferencia;
        this.transacao = transacao;
    }

    @Override
    public void afterSingletonsInstantiated() {
        transacao.executeWithoutResult(status -> criarSeNaoExistir());
    }

    private void criarSeNaoExistir() {
        if (vazio(dados.login()) || vazio(dados.senha()) || vazio(dados.nome())) {
            LOG.info("Vendedor de demonstração não criado: preencha DEMO_VENDEDOR_NOME, DEMO_VENDEDOR_LOGIN "
                    + "e DEMO_VENDEDOR_SENHA no .env se quiser um.");
            return;
        }
        String login = Logins.normalizar(dados.login());
        if (!Logins.podeExistir(login) || !ConferenciaDeSenha.cabe(dados.senha())) {
            LOG.warn("Vendedor de demonstração não criado: DEMO_VENDEDOR_LOGIN ou DEMO_VENDEDOR_SENHA inválidos.");
            return;
        }
        if (usuarios.findByLogin(login).isPresent()) {
            return;
        }
        usuarios.save(Usuario.novo(dados.nome().strip(), login, conferencia.gerarHash(dados.senha()), "VENDEDOR", null));
        LOG.info("Vendedor de demonstração criado (só em desenvolvimento): login {}", login);
    }

    private static boolean vazio(String valor) {
        return valor == null || valor.isBlank() || valor.strip().equalsIgnoreCase(VALOR_DE_EXEMPLO);
    }
}
