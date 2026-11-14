package br.com.coronasemijoias.api.contaspagar;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Permissões (tarefa 7.7): tudo que é dinheiro é só da dona. O vendedor recebe 403 em toda rota nova (contas a pagar, pagamentos,
 * categorias do caixa, lançamentos manuais, saldo e fechar semana) e quem não entrou recebe 401. A API valida, não só a tela.
 */
class PermissoesDasContasAPagarECaixaTest extends ApoioDasContasAPagar {

    private List<MockHttpServletRequestBuilder> rotas() {
        UUID id = UUID.randomUUID();
        String corpo = "{}";
        return List.of(
                post("/api/dona/compras/" + id + "/contas-a-pagar").contentType(MediaType.APPLICATION_JSON).content(corpo),
                get("/api/dona/compras/" + id + "/contas-a-pagar"),
                get("/api/dona/contas-a-pagar"),
                get("/api/dona/contas-a-pagar/" + id),
                post("/api/dona/contas-a-pagar/" + id + "/pagamentos").contentType(MediaType.APPLICATION_JSON).content(corpo),
                post("/api/dona/contas-a-pagar/pagamentos/" + id + "/estorno").contentType(MediaType.APPLICATION_JSON).content(corpo),
                get("/api/dona/caixa/categorias"),
                post("/api/dona/caixa/categorias").contentType(MediaType.APPLICATION_JSON).content(corpo),
                put("/api/dona/caixa/categorias/" + id).contentType(MediaType.APPLICATION_JSON).content(corpo),
                post("/api/dona/caixa/categorias/" + id + "/inativar"),
                post("/api/dona/caixa/categorias/" + id + "/reativar"),
                post("/api/dona/caixa/lancamentos").contentType(MediaType.APPLICATION_JSON).content(corpo),
                post("/api/dona/caixa/lancamentos/" + id + "/estorno").contentType(MediaType.APPLICATION_JSON).content(corpo),
                get("/api/dona/caixa/saldo"),
                get("/api/dona/caixa/lancamentos"),
                post("/api/dona/semanas/" + id + "/fechar").contentType(MediaType.APPLICATION_JSON).content(corpo));
    }

    @Test
    void vendedorRecebe403ESemLoginRecebe401EmTodasAsRotasNovas() throws Exception {
        RequestPostProcessor vendedor = usuarios.comoVendedor();
        for (MockHttpServletRequestBuilder rota : rotas()) {
            mvc.perform(rota.with(vendedor)).andExpect(status().isForbidden());
        }
        for (MockHttpServletRequestBuilder rota : rotas()) {
            mvc.perform(rota).andExpect(status().isUnauthorized());
        }
    }
}
