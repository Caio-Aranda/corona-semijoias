package br.com.coronasemijoias.api.acertos;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Permissões do acerto, validadas na API (tarefa 7.4; ADR-0012, item 16; ajuste 2 da aprovação): 403 cruzado em todas as
 * rotas novas, 401 sem login e 404 para dado de outro vendedor. Escrito antes dos controladores.
 */
class PermissoesDoAcertoTest extends ApoioDoAcerto {

    private static final String UM = UUID.randomUUID().toString();

    private record Rota(String metodo, String caminho) {
    }

    private static final List<Rota> DA_DONA = List.of(
            new Rota("POST", ACERTOS_DA_DONA), new Rota("GET", ACERTOS_DA_DONA), new Rota("GET", ACERTOS_DA_DONA + "/" + UM),
            new Rota("POST", ACERTOS_DA_DONA + "/" + UM + "/leituras"), new Rota("POST", ACERTOS_DA_DONA + "/" + UM + "/desistir"));
    private static final List<Rota> DO_VENDEDOR = List.of(
            new Rota("POST", ACERTOS_DO_VENDEDOR), new Rota("GET", ACERTOS_DO_VENDEDOR), new Rota("GET", ACERTOS_DO_VENDEDOR + "/" + UM),
            new Rota("POST", ACERTOS_DO_VENDEDOR + "/" + UM + "/leituras"));

    private org.springframework.test.web.servlet.ResultActions chamar(Rota r,
            org.springframework.test.web.servlet.request.RequestPostProcessor quem) throws Exception {
        var pedido = "GET".equals(r.metodo()) ? get(r.caminho()) : post(r.caminho()).contentType(MediaType.APPLICATION_JSON).content("{}");
        return quem == null ? mvc.perform(pedido) : mvc.perform(pedido.with(quem));
    }

    @Test
    void vendedorEmTodaRotaDaDonaRecebe403() throws Exception {
        UsuarioDeTeste vendedor = usuarios.criarVendedor();
        for (Rota r : DA_DONA) {
            chamar(r, como(vendedor)).andExpect(status().isForbidden());
        }
    }

    @Test
    void donaEmTodaRotaDoVendedorRecebe403() throws Exception {
        for (Rota r : DO_VENDEDOR) {
            chamar(r, dona()).andExpect(status().isForbidden());
        }
    }

    @Test
    void semLoginRecebe401EmTodasAsRotas() throws Exception {
        for (Rota r : DA_DONA) {
            chamar(r, null).andExpect(status().isUnauthorized());
        }
        for (Rota r : DO_VENDEDOR) {
            chamar(r, null).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void acertoDeOutroVendedorDevolve404EmTodasAsRotasDoVendedor() throws Exception {
        UsuarioDeTeste dono = usuarios.criarVendedor();
        UUID peca = peca("20.00", 5);
        UUID semana = semana();
        maletaConferida(dono, semana, peca, 2);
        UUID acerto = iniciar(dono, semana);
        UsuarioDeTeste intruso = usuarios.criarVendedor();
        ler(ACERTOS_DO_VENDEDOR + "/" + acerto, como(intruso)).andExpect(status().isNotFound());
        postar(ACERTOS_DO_VENDEDOR + "/" + acerto + "/leituras", como(intruso),
                mapa("id", UUID.randomUUID(), "produtoId", peca, "quantidade", 1, "estadoPeca", "BOA")).andExpect(status().isNotFound());
        // Pedir o acerto repetindo o id de outro vendedor também não revela nada: 409 de id usado, nunca o conteúdo.
        postar(ACERTOS_DO_VENDEDOR, como(intruso), mapa("id", acerto)).andExpect(status().isConflict());
    }
}
