package br.com.coronasemijoias.api.contasreceber;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Permissões das contas a receber (tarefa 7.3), validadas na API: perfil errado 403, sem login 401, venda, recebimento ou
 * conta de outro vendedor 404. Escrito antes do código.
 */
class PermissoesDasContasTest extends ApoioDasContas {

    private static final String LISTA_DO_VENDEDOR = "/api/vendedor/contas-receber";
    private static final String LISTA_DA_DONA = "/api/dona/contas-receber";

    @Test
    void adminNasRotasDoVendedorDa403() throws Exception {
        APrazo c = vendaDeCemEmTres();
        ler(LISTA_DO_VENDEDOR, dona()).andExpect(status().isForbidden());
        postar(RECEBIMENTOS.formatted(c.venda()), dona(), mapa("id", UUID.randomUUID(), "valor", "1.00", "forma", "PIX"))
                .andExpect(status().isForbidden());
        postar(ESTORNO_DO_VENDEDOR.formatted(UUID.randomUUID()), dona(), mapa("id", UUID.randomUUID(), "motivo", "x"))
                .andExpect(status().isForbidden());
    }

    @Test
    void vendedorNasRotasDaDonaDa403() throws Exception {
        APrazo c = vendaDeCemEmTres();
        ler(LISTA_DA_DONA, como(c.vendedor())).andExpect(status().isForbidden());
        postar(ESTORNO_DA_DONA.formatted(UUID.randomUUID()), como(c.vendedor()), mapa("id", UUID.randomUUID(), "motivo", "x"))
                .andExpect(status().isForbidden());
    }

    @Test
    void semLoginDa401() throws Exception {
        UUID qualquer = UUID.randomUUID();
        var invalido = UsuariosDeTeste.comToken("invalido");
        ler(LISTA_DO_VENDEDOR, invalido).andExpect(status().isUnauthorized());
        ler(LISTA_DA_DONA, invalido).andExpect(status().isUnauthorized());
        postar(RECEBIMENTOS.formatted(qualquer), invalido, mapa("id", qualquer, "valor", "1.00", "forma", "PIX"))
                .andExpect(status().isUnauthorized());
        postar(ESTORNO_DO_VENDEDOR.formatted(qualquer), invalido, mapa("id", qualquer, "motivo", "x"))
                .andExpect(status().isUnauthorized());
        postar(ESTORNO_DA_DONA.formatted(qualquer), invalido, mapa("id", qualquer, "motivo", "x")).andExpect(status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(LISTA_DA_DONA))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void vendaERecebimentoDeOutroVendedorDa404() throws Exception {
        APrazo c = vendaDeCemEmTres();
        UUID recebimento = receberOk(c, "10.00");
        var intruso = usuarios.criarVendedor();
        ler("/api/vendedor/vendas/" + c.venda(), como(intruso)).andExpect(status().isNotFound());
        postar(RECEBIMENTOS.formatted(c.venda()), como(intruso), mapa("id", UUID.randomUUID(), "valor", "1.00", "forma", "PIX"))
                .andExpect(status().isNotFound());
        postar(ESTORNO_DO_VENDEDOR.formatted(recebimento), como(intruso), mapa("id", UUID.randomUUID(), "motivo", "x"))
                .andExpect(status().isNotFound());
        // A lista do intruso não traz nada do outro.
        var lista = corpoDe(ler(LISTA_DO_VENDEDOR + "?vendaId=" + c.venda(), como(intruso)).andExpect(status().isOk()));
        org.assertj.core.api.Assertions.assertThat(lista).isEmpty();
    }
}
