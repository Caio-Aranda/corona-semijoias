package br.com.coronasemijoias.api.acertos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/**
 * As regras do acerto que o PRÓPRIO BANCO garante (tarefa 7.4; ADR-0012, itens 2, 3, 4, 13 e 14; invariantes 1, 2, 4, 5 e 6
 * do plano), com SQL direto: o serviço pode errar, o banco não deixa gravar errado. Escrito antes da migração V043.
 */
class AcertosNoBancoTest extends ApoioDoAcerto {

    private UsuarioDeTeste vendedor;
    private UUID semana;
    private UUID peca;
    private UUID maleta;

    @BeforeEach
    void umaMaletaConferida() throws Exception {
        vendedor = usuarios.criarVendedor();
        semana = semana();
        peca = peca("50.00", 10);
        maleta = maletaConferida(vendedor, semana, peca, 5);
    }

    // --- o acerto ---

    @Test
    void nasceEmAcertoSemDesistencia() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO corona.acertos (id, vendedor_id, semana_id, status, pedido_pelo_vendedor, iniciado_em, iniciado_por)
                VALUES (?, ?, ?, 'DESISTIDO', false, now(), ?)""", id, vendedor.vendedorId(), semana, usuarios.dona().id()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO corona.acertos (id, vendedor_id, semana_id, status, pedido_pelo_vendedor, iniciado_em, iniciado_por)
                VALUES (?, ?, ?, 'ACERTADO', false, now(), ?)""", id, vendedor.vendedorId(), semana, usuarios.dona().id()))
                .isInstanceOf(DataAccessException.class);
        assertThatCode(() -> acertoNoBanco(vendedor, semana)).doesNotThrowAnyException();
    }

    @Test
    void umSoAcertoEmAndamentoPorVendedor() throws Exception {
        acertoNoBanco(vendedor, semana);
        assertThatThrownBy(() -> acertoNoBanco(vendedor, semana())).isInstanceOf(DataAccessException.class);
    }

    @Test
    void umAcertoPorSemanaMasAceitaDepoisDeDesistir() {
        UUID primeiro = acertoNoBanco(vendedor, semana);
        desistirNoBanco(primeiro);
        UUID segundo = acertoNoBanco(vendedor, semana);
        // Outro acerto na mesma semana, com um já em andamento, recusado.
        assertThatThrownBy(() -> acertoNoBanco(vendedor, semana)).isInstanceOf(DataAccessException.class);
        desistirNoBanco(segundo);
        assertThat(contar("SELECT count(*) FROM corona.acertos WHERE vendedor_id = ?", vendedor.vendedorId())).isEqualTo(2);
    }

    @Test
    void semanaFechadaRecusa() {
        br.com.coronasemijoias.api.testes.SemanasDeTeste.fecharNoBanco(jdbc, semana, usuarios.dona().id());
        assertThatThrownBy(() -> acertoNoBanco(vendedor, semana)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void soMudaDeEmAcertoParaDesistido() {
        UUID id = acertoNoBanco(vendedor, semana);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.acertos SET status = 'ACERTADO' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.acertos SET semana_id = ? WHERE id = ?", semana(), id))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.acertos SET pedido_pelo_vendedor = true WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.acertos SET iniciado_em = now() - interval '1 day' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.acertos SET deleted_at = now() WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class);
        desistirNoBanco(id);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.acertos SET status = 'EM_ACERTO' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.acertos SET motivo_desistencia = 'outro' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void desistirExigeMotivoEOsDadosDaDesistencia() {
        UUID id = acertoNoBanco(vendedor, semana);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE corona.acertos SET status = 'DESISTIDO', desistencia_id = ?, desistido_em = now(), desistido_por = ?,
                       motivo_desistencia = '  ' WHERE id = ?""", UUID.randomUUID(), usuarios.dona().id(), id))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.acertos SET status = 'DESISTIDO' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void nuncaApagaAcerto() {
        UUID id = acertoNoBanco(vendedor, semana);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM corona.acertos WHERE id = ?", id)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void ovendedorDoAcertoNaoMuda() {
        UUID id = acertoNoBanco(vendedor, semana);
        UUID outro = usuarios.criarCadastroDeVendedor();
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.acertos SET vendedor_id = ? WHERE id = ?", outro, id))
                .isInstanceOf(DataAccessException.class);
    }

    // --- as leituras ---

    @Test
    void leituraSoEmAcertoEmAndamento() {
        UUID id = acertoNoBanco(vendedor, semana);
        assertThatCode(() -> lerNoBanco(id, peca, 2, "BOA")).doesNotThrowAnyException();
        desistirNoBanco(id);
        assertThatThrownBy(() -> lerNoBanco(id, peca, 1, "BOA")).isInstanceOf(DataAccessException.class);
    }

    @Test
    void leituraNuncaMudaNemApaga() {
        UUID id = acertoNoBanco(vendedor, semana);
        lerNoBanco(id, peca, 2, "BOA");
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.leituras_acerto SET quantidade = 3 WHERE acerto_id = ?", id))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM corona.leituras_acerto WHERE acerto_id = ?", id))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void contagemNuncaFicaNegativaPorPecaEEstado() {
        UUID id = acertoNoBanco(vendedor, semana);
        lerNoBanco(id, peca, 2, "BOA");
        // Desfazer uma leitura é leitura negativa, mas só até zerar, e por estado da peça.
        assertThatThrownBy(() -> lerNoBanco(id, peca, -3, "BOA")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> lerNoBanco(id, peca, -1, "DEFEITO")).isInstanceOf(DataAccessException.class);
        assertThatCode(() -> lerNoBanco(id, peca, -2, "BOA")).doesNotThrowAnyException();
        assertThat(contar("SELECT coalesce(sum(quantidade), 0) FROM corona.leituras_acerto WHERE acerto_id = ?", id)).isZero();
    }

    @Test
    void leituraComQuantidadeZeroOuAcimaDoLimiteOuEstadoDesconhecidoRecusada() {
        UUID id = acertoNoBanco(vendedor, semana);
        assertThatThrownBy(() -> lerNoBanco(id, peca, 0, "BOA")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> lerNoBanco(id, peca, 10_001, "BOA")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> lerNoBanco(id, peca, 1, "NOVA")).isInstanceOf(DataAccessException.class);
    }

    @Test
    void oAcertoDaLeituraNaoMuda() {
        UUID id = acertoNoBanco(vendedor, semana);
        lerNoBanco(id, peca, 2, "BOA");
        desistirNoBanco(id);
        UUID outro = acertoNoBanco(vendedor, semana);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.leituras_acerto SET acerto_id = ? WHERE acerto_id = ?", outro, id))
                .isInstanceOf(DataAccessException.class);
    }

    // --- a ligação da maleta ---

    @Test
    void ligarEDesligarVoltaAoStatusDeAntes() {
        UUID id = acertoNoBanco(vendedor, semana);
        ligarNoBanco(maleta, id);
        assertThat(statusDaMaleta(maleta)).isEqualTo("EM_ACERTO");
        assertThat(jdbc.queryForObject("SELECT status_antes_do_acerto FROM corona.remessas WHERE id = ?", String.class, maleta))
                .isEqualTo("CONFERIDA");
        jdbc.update("UPDATE corona.remessas SET status = status_antes_do_acerto, acerto_id = NULL, status_antes_do_acerto = NULL WHERE id = ?",
                maleta);
        assertThat(statusDaMaleta(maleta)).isEqualTo("CONFERIDA");
    }

    @Test
    void ligarDeUmAcertoDeOutroVendedorOuDesistidoRecusado() throws Exception {
        UsuarioDeTeste outro = usuarios.criarVendedor();
        UUID doOutro = acertoNoBanco(outro, semana());
        assertThatThrownBy(() -> ligarNoBanco(maleta, doOutro)).isInstanceOf(DataAccessException.class);
        UUID meu = acertoNoBanco(vendedor, semana);
        desistirNoBanco(meu);
        assertThatThrownBy(() -> ligarNoBanco(maleta, meu)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void entrarEmAcertoSemLigarOuSairPorOutroCaminhoRecusado() {
        UUID id = acertoNoBanco(vendedor, semana);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.remessas SET status = 'EM_ACERTO' WHERE id = ?", maleta))
                .isInstanceOf(DataAccessException.class);
        ligarNoBanco(maleta, id);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE corona.remessas SET status = 'CANCELADA', motivo_cancelamento = 'x', acerto_id = NULL,
                       status_antes_do_acerto = NULL WHERE id = ?""", maleta)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE corona.remessas SET status = 'ENVIADA', acerto_id = NULL, status_antes_do_acerto = NULL WHERE id = ?""", maleta))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void trocarDeAcertoRecusado() throws Exception {
        UUID velho = acertoNoBanco(vendedor, semana);
        desistirNoBanco(velho);
        UUID id = acertoNoBanco(vendedor, semana);
        ligarNoBanco(maleta, id);
        assertThatThrownBy(() -> jdbc.update("UPDATE corona.remessas SET acerto_id = ? WHERE id = ?", velho, maleta))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void remessaEmRascunhoNaoLiga() throws Exception {
        UUID rascunho = rascunho(vendedor, semana(), "REPOSICAO", peca, 1);
        UUID id = acertoNoBanco(vendedor, semana);
        assertThatThrownBy(() -> ligarNoBanco(rascunho, id)).isInstanceOf(DataAccessException.class);
    }

    // --- a regra de corte da venda ---

    @Test
    void vendaComHoraDepoisDoInicioNaMaletaEmAcertoRecusada() throws Exception {
        UUID cliente = cliente(vendedor);
        UUID id = acertoNoBanco(vendedor, semana);
        ligarNoBanco(maleta, id);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO corona.vendas (id, vendedor_id, cliente_id, remessa_id, subtotal, total, vendida_em)
                VALUES (?, ?, ?, ?, 50, 50, now() + interval '1 minute')""", UUID.randomUUID(), vendedor.vendedorId(), cliente, maleta))
                .isInstanceOf(DataAccessException.class);
        assertThatCode(() -> jdbc.update("""
                INSERT INTO corona.vendas (id, vendedor_id, cliente_id, remessa_id, subtotal, total, vendida_em)
                VALUES (?, ?, ?, ?, 50, 50, now() - interval '1 hour')""", UUID.randomUUID(), vendedor.vendedorId(), cliente, maleta))
                .doesNotThrowAnyException();
    }
}
