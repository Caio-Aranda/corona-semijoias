package br.com.coronasemijoias.api.consignado;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A configuração das telas (acertoDoVendedorVisivel falso por padrão; as rotas antigas continuam respondendo) e as leituras da
 * dona sobre o consignado (ADR-0016, itens 11, 14 e 15). Escritos antes do código.
 */
class ConfiguracaoDasTelasETest extends ApoioDoConsignado {

    @Test
    void acertoDoVendedorVisivelEFalsoPorPadraoEAsRotasAntigasContinuamRespondendo() throws Exception {
        ler("/api/configuracoes/telas", dona()).andExpect(status().isOk()).andExpect(jsonPath("$.acertoDoVendedorVisivel").value(false));
        // As rotas do fluxo antigo seguem vivas (nada é apagado, só escondido).
        ler("/api/dona/acertos", dona()).andExpect(status().is2xxSuccessful());
        jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = 1 WHERE chave = 'acerto_do_vendedor_visivel'");
        try {
            ler("/api/configuracoes/telas", dona()).andExpect(jsonPath("$.acertoDoVendedorVisivel").value(true));
        } finally {
            jdbc.update("UPDATE corona.configuracoes SET valor_inteiro = 0 WHERE chave = 'acerto_do_vendedor_visivel'");
        }
    }

    @Test
    void soAdminLeAsTelasEOConsignado() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        ler("/api/configuracoes/telas", como(v)).andExpect(status().isForbidden());
        ler("/api/consignado/notas", como(v)).andExpect(status().isForbidden());
        ler("/api/dona/consignado/alertas", como(v)).andExpect(status().isForbidden());
    }

    @Test
    void leiturasDaDonaMostramNotasDetalheSemanasClienteEAlertas() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID cliente = cliente(v);
        UUID peca = peca("100.00", 20);
        UUID maleta = maletaEnviada(v, peca, 10);
        UUID semana = abrirSemana(v, 65, relogio.instant().minusSeconds(100));
        UUID nota = criarNota(v, semana, 21, cliente, item(peca, maleta, 5, "90.00")); // preço diferente: alerta
        UUID acerto = acertar(v, nota, semana, "30", List.of(volta(peca, maleta, 2)), 53, paga("PIX", "100.00"));

        ler("/api/consignado/notas?vendedorId=" + v.vendedorId(), dona()).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].numero").value("0000065-21")).andExpect(jsonPath("$[0].situacao").value("PENDENTE"))
                .andExpect(jsonPath("$[0].valorDx").value(500.0));
        ler("/api/consignado/notas/" + nota, dona()).andExpect(status().isOk())
                .andExpect(jsonPath("$.linhas[0].dx").value(5)).andExpect(jsonPath("$.acertos[0].bruto").value(210.0))
                .andExpect(jsonPath("$.acertos[0].saldoDoDebito").value(110.0)).andExpect(jsonPath("$.recibos[0].sequencia").value(53));
        ler("/api/consignado/vendedores/" + v.vendedorId() + "/semanas", dona()).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].numero").value(65)).andExpect(jsonPath("$[0].notas").value(1));
        ler("/api/consignado/semanas/" + semana, dona()).andExpect(status().isOk()).andExpect(jsonPath("$.recebido").value(100.0));
        ler("/api/consignado/clientes/" + cliente + "/situacao", dona()).andExpect(status().isOk())
                .andExpect(jsonPath("$.pendencia").value(110.0));
        String alertas = ler("/api/dona/consignado/alertas?soNaoVistos=true", dona()).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat(alertas).contains("PRECO_DIFERENTE_DA_MALETA").contains(v.vendedorId().toString());
        ler("/api/dona/consignado/conciliacoes", dona()).andExpect(status().isOk()).andExpect(jsonPath("$.conferencias.length()").value(7));
        assertThat(acerto).isNotNull();
    }
}
