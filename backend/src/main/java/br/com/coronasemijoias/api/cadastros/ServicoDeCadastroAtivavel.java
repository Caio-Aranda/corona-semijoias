package br.com.coronasemijoias.api.cadastros;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cadastro que se inativa e se reativa (tarefa 1.14). Inativar é permitido mesmo com o cadastro em uso e
 * preserva os vínculos: só as listas de escolha escondem o inativo. Cada mudança entra na auditoria, na
 * mesma transação, e usa a versão que a tela leu quando ela vem.
 */
public abstract class ServicoDeCadastroAtivavel<E extends Ativavel> extends ServicoDeCadastro<E> {

    private final ServicoDeAuditoria auditoria;
    private final String tabela;

    protected ServicoDeCadastroAtivavel(RepositorioDeCadastro<E> repositorio, Clock relogio,
            ServicoDeAuditoria auditoria, String tabela) {
        super(repositorio, relogio);
        this.auditoria = auditoria;
        this.tabela = tabela;
    }

    /**
     * Muda a situação. {@code versao} é opcional: se vier e já não for a atual, recusa com 409; sem ela vale a
     * versão de agora. Pedir a situação que o cadastro já tem não grava nada.
     */
    @Transactional
    public E definirAtivo(UUID id, Integer versao, boolean ativo, UUID quem) {
        E atual = obter(id);
        int versaoLida = versao == null ? atual.getVersion() : Validacoes.versaoDoPedido(versao);
        if (atual.isAtivo() == ativo && atual.getVersion() == versaoLida) {
            return atual;
        }
        E editado = editarComVersao(id, versaoLida, e -> e.definirAtivo(ativo));
        auditoria.registrar(Ator.usuario(quem), ativo ? "REATIVADO" : "INATIVADO", tabela, id,
                Map.of("ativo", !ativo), Map.of("ativo", ativo));
        return editado;
    }
}
