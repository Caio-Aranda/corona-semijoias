package br.com.coronasemijoias.api.consignado;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.remessas.ConsultasDaMaleta;
import br.com.coronasemijoias.api.remessas.PecasEmMaletas;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/**
 * C1 da revisão de 27/11/2026: o limite do representante conta tudo o que está com ele, inclusive as peças que ele deixou
 * com as clientes (notas sem acerto). Só o acerto (venda ou devolução) e a devolução à loja baixam. Escritos antes do código.
 */
class LimiteComPecasComClientesTest extends ApoioDoConsignado {

    @Autowired
    ConsultasDaMaleta consultas;

    @Autowired
    PecasEmMaletas emMaletas;

    private UUID rascunhoCom(UsuarioDeTeste v, UUID produto, int quantidade) throws Exception {
        JsonNode r = corpoDe(postar(REMESSAS, dona(), mapa("id", UUID.randomUUID(), "vendedorId", v.vendedorId(),
                "semanaId", semana(), "tipo", "NOVA")).andExpect(status().isCreated()));
        mvc.perform(put(REMESSAS + "/" + r.get("id").asString() + "/itens/" + produto).with(dona())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapa("quantidade", quantidade, "version", r.get("version").asInt()))))
                .andExpect(status().isOk());
        return UUID.fromString(r.get("id").asString());
    }

    private void limite(UsuarioDeTeste v, String valor) {
        jdbc.update("UPDATE corona.vendedores SET limite_consignacao = ?::numeric, limite_consignacao_ilimitado = false WHERE id = ?",
                valor, v.vendedorId());
    }

    @Test
    void pecasComAsClientesContinuamContandoNoLimite() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("100.00", 20);
        UUID maleta = maletaEnviada(v, anel, 4);
        limite(v, "700.00");
        UUID semana = abrirSemana(v);
        criarNota(v, semana, 1, cliente(v), item(anel, maleta, 4, "100.00"));

        // As 4 peças estão com a cliente: continuam com o representante para o limite.
        assertThat(consultas.valorEmAberto(v.vendedorId())).isEqualByComparingTo("400.00");
        ConsultasDaMaleta.EmAberto aberto = consultas.emAberto(v.vendedorId());
        assertThat(aberto.valorEmAberto()).isEqualByComparingTo("400.00");
        assertThat(aberto.limiteRestante()).isEqualByComparingTo("300.00");
        assertThat(aberto.maletas()).singleElement().satisfies(m -> {
            assertThat(m.pecasEmAberto()).isEqualTo(4);
            assertThat(m.valorEmAberto()).isEqualByComparingTo("400.00");
        });

        // Uma segunda maleta de R$ 400 passa o limite (400 + 400 > 700) e é recusada com a palavra do glossário.
        UUID segunda = rascunhoCom(v, anel, 4);
        postar(REMESSAS + "/" + segunda + "/enviar", dona(), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("LIMITE_DE_CONSIGNACAO_EXCEDIDO"))
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("representante")))
                .andExpect(jsonPath("$.detail").value(Matchers.not(Matchers.containsString("vendedor"))));
    }

    @Test
    void oAcertoBaixaOVendidoEODevolvidoVoltaParaAMaleta() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("100.00", 20);
        UUID maleta = maletaEnviada(v, anel, 4);
        UUID semana = abrirSemana(v);
        UUID nota = criarNota(v, semana, 1, cliente(v), item(anel, maleta, 3, "100.00"));
        assertThat(consultas.valorEmAberto(v.vendedorId())).isEqualByComparingTo("400.00");

        // Acerto: 1 volta, 2 vendidos. Fica 1 na maleta + 1 devolvido = 2 peças com ele.
        acertar(v, nota, semana, "30", List.of(volta(anel, maleta, 1)), null);
        assertThat(consultas.valorEmAberto(v.vendedorId())).isEqualByComparingTo("200.00");
    }

    @Test
    void maletaFechadaComPecaAindaComClienteContaNoLimite() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("100.00", 20);
        UUID maleta = maletaEnviada(v, anel, 2);
        UUID semana = abrirSemana(v);
        criarNota(v, semana, 1, cliente(v), item(anel, maleta, 2, "100.00"));
        jdbc.update("UPDATE corona.remessas SET status = 'FECHADA' WHERE id = ?", maleta);

        assertThat(consultas.valorEmAberto(v.vendedorId())).isEqualByComparingTo("200.00");
        assertThat(consultas.emAberto(v.vendedorId()).maletas()).singleElement()
                .satisfies(m -> assertThat(m.pecasEmAberto()).isEqualTo(2));
    }

    /** C6: a ficha do produto conta as peças com as clientes junto das que estão na maleta. */
    @Test
    void fichaDoProdutoContaAsPecasComAsClientes() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("100.00", 20);
        UUID maleta = maletaEnviada(v, anel, 4);
        UUID semana = abrirSemana(v);
        criarNota(v, semana, 1, cliente(v), item(anel, maleta, 2, "100.00"));
        assertThat(emMaletas.de(anel).total()).isEqualTo(4);

        UUID nota = criarNota(v, semana, 2, cliente(v), item(anel, maleta, 2, "100.00"));
        assertThat(emMaletas.de(anel).total()).isEqualTo(4);
        assertThat(emMaletas.de(anel).vendedores()).singleElement().satisfies(d -> assertThat(d.quantidade()).isEqualTo(4));

        // Acerto da nota 2: 1 volta à maleta e 1 vendida sai de vez.
        acertar(v, nota, semana, "30", List.of(volta(anel, maleta, 1)), null);
        assertThat(emMaletas.de(anel).total()).isEqualTo(3);
    }

    @Test
    void pecaQueVoltouDaClienteParaMaletaFechadaContaAteADonaLer() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("100.00", 20);
        UUID maleta = maletaEnviada(v, anel, 2);
        UUID semana = abrirSemana(v);
        UUID nota = criarNota(v, semana, 1, cliente(v), item(anel, maleta, 2, "100.00"));
        jdbc.update("UPDATE corona.remessas SET status = 'FECHADA' WHERE id = ?", maleta);
        // A cliente devolve 1 e fica com 1 (vendida): a devolvida está com o representante até a dona ler.
        acertar(v, nota, semana, "30", List.of(volta(anel, maleta, 1)), null);
        assertThat(consultas.valorEmAberto(v.vendedorId())).isEqualByComparingTo("100.00");
        assertThat(consultas.emAberto(v.vendedorId()).maletas()).singleElement()
                .satisfies(m -> assertThat(m.pecasEmAberto()).isEqualTo(1));
    }
}
