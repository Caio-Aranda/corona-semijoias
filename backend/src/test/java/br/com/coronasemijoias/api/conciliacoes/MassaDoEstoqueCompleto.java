package br.com.coronasemijoias.api.conciliacoes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/**
 * A massa do estoque completo (tarefa 8.3), por cima da massa da 7.8 (que continua inteira: as onze têm de dar "Tudo certo"
 * nela também). Tudo pelas rotas de verdade, nas peças A e B de um vendedor só. O resultado à mão, passo a passo:
 *
 * <pre>
 *  1. Compra K1: 10 A e 5 B. Compra K2: 2 B, desfeita (não conta).       A 10 · B 5
 *  2. Ajuste manual +1 B; avaria manual de 1 A; inventário conta 8 A      A 10-1-1 = 8 · B 6
 *     (esperado 9, ajuste -1).
 *  3. Maleta M1 envia 4 A e 3 B; M2 (reposição, mesma semana) envia 2 A;   loja A 8-4-2 = 2 · B 6-3 = 3
 *     M3 envia 1 A e é cancelada (a baixa é estornada).
 *  4. V1 (M1) vende 2 A; V2 (M1) vende 1 B e é desfeita. D1 devolve 1 A de V1: a peça é "para a loja"
 *     (a dona registra a devolução e a peça não volta ao saldo da maleta). Troca T1 sobre V1: o outro A volta
 *     à maleta (TROCA_ENTRADA) e leva 1 B (V3). V4 (M1) vende 1 B; D2 devolve esse B com defeito (também para a loja).
 *  5. Devolução à dona de M2: 1 A (a outra devolução de 1 A é desfeita).     loja A 2+1 = 3
 *
 * <b>Retrato antes do acerto</b>
 *  C11 A: loja 3 + maletas (M1 3, M2 1) = 7; comprado 10 - avaria 1 - inventário 1 - vendido 2 + devolvido à maleta 1 = 7.
 *  C11 B: loja 3 + M1 1 = 4; 5 + 1 - vendido 2 (V3 e V4; o B da D2 está a caminho da loja) = 4.
 *  C12 M1 A: 4 - 2 + 1 = 3; M1 B: 3 - 2 = 1; M2 A: 2 - 1 = 1; M3 sem peça (baixa estornada).
 *
 * <b>Acerto</b> (M1 e M2, uma semana só): A conta 4 boas e a falta de 1 cai em M1 (a loja assume); B conta 2 com defeito
 * (um em M1 e o da D2, na linha "peças para a loja"), os dois aprovados. A linha da loja de A traz o A da D1.
 *
 * <b>Retrato depois</b>
 *  C11 A: loja 3+4 = 7 e maletas 0; 10 - 1 - 1 - 2 + 1 + recebido na loja 1 - falta 1 = 7.
 *  C11 B: loja 3 + 0 = 3; 6 - 2 + recebido na loja 1 - defeito aprovado 2 = 3.
 *  C12 M1 A: 4 - 2 + 1 - devolvido à dona 2 - falta 1 = 0; M1 B: 3 - 2 - 1 = 0; M2 A: 2 - 1 - 1 = 0; M3 cancelada: sem peça.
 * </pre>
 */
abstract class MassaDoEstoqueCompleto extends MassaDaFase7 {

    UsuarioDeTeste vendedorDoEstoque;
    UUID pecaA;
    UUID pecaB;
    UUID compraK1;
    UUID maletaM1;
    UUID maletaM2;
    UUID maletaM3;
    UUID vendaV1;
    UUID vendaV4;
    UUID devolucaoD1;
    UUID devolucaoD2;
    UUID devolucaoDaTroca;
    UUID devolucaoDaDonaDeM2;
    UUID acertoDoEstoque;
    UUID movimentoDaCompraK1;

    @BeforeAll
    void montarOEstoqueAntesDoAcerto() throws Exception {
        UUID fornecedor = fornecedor();
        pecaA = peca("50.00", 0);
        pecaB = peca("30.00", 0);
        compraK1 = comprar(fornecedor, List.of(linhaDeCompra(pecaA, 10), linhaDeCompra(pecaB, 5)));
        UUID k2 = comprar(fornecedor, List.of(linhaDeCompra(pecaB, 2)));
        postarDona(COMPRAS + "/" + k2 + "/desfazer", mapa("id", UUID.randomUUID(), "motivo", "Veio errada")).andExpect(status().isCreated());

        postarDona("/api/dona/estoque/ajustes", mapa("produtoId", pecaB, "diferenca", 1, "motivo", "Achei uma caixa"))
                .andExpect(status().isCreated());
        postarDona("/api/dona/estoque/movimentacoes", mapa("produtoId", pecaA, "tipo", "AVARIA", "quantidade", 1, "motivo", "Quebrou"))
                .andExpect(status().isCreated());
        inventarioDe(pecaA, 8);

        vendedorDoEstoque = novoVendedor();
        UUID cliente = cliente(vendedorDoEstoque);
        UUID semanaDoAcerto = semana();
        maletaM1 = maletaConferida(vendedorDoEstoque, semanaDoAcerto, pecaA, 4, pecaB, 3);
        maletaM2 = maletaConferida(vendedorDoEstoque, semanaDoAcerto, pecaA, 2);
        maletaM3 = maletaCancelada(vendedorDoEstoque, pecaA, 1);

        vendaV1 = vender(vendedorDoEstoque, maletaM1, cliente, pecaA, 2, "100.00");
        UUID v2 = vender(vendedorDoEstoque, maletaM1, cliente, pecaB, 1, "30.00");
        postarDona("/api/dona/vendas/" + v2 + "/estorno", mapa("id", UUID.randomUUID(), "motivo", "erro")).andExpect(status().isCreated());
        devolucaoD1 = UUID.randomUUID();
        postarDona("/api/dona/devolucoes", mapa("id", devolucaoD1, "vendaId", vendaV1, "motivo", "ARREPENDIMENTO", "formaReembolso", "DINHEIRO",
                "itens", List.of(mapa("produtoId", pecaA, "quantidade", 1, "estado", "BOA")))).andExpect(status().isCreated());
        devolucaoDaTroca = UUID.randomUUID();
        postar("/api/vendedor/trocas", como(vendedorDoEstoque),
                mapa("id", UUID.randomUUID(), "creditoId", UUID.randomUUID(), "pagamentoDoCreditoId", UUID.randomUUID(),
                        "devolucao", mapa("id", devolucaoDaTroca, "vendaId", vendaV1, "motivo", "ARREPENDIMENTO",
                                "itens", List.of(mapa("produtoId", pecaA, "quantidade", 1, "estado", "BOA"))),
                        "venda", mapa("id", UUID.randomUUID(), "remessaId", maletaM1, "itens", List.of(linha(pecaB, 1)),
                                "pagamentos", List.of()))).andExpect(status().isCreated());
        vendaV4 = vender(vendedorDoEstoque, maletaM1, cliente, pecaB, 1, "30.00");
        devolucaoD2 = UUID.randomUUID();
        postarDona("/api/dona/devolucoes", mapa("id", devolucaoD2, "vendaId", vendaV4, "motivo", "DEFEITO", "formaReembolso", "DINHEIRO",
                "itens", List.of(mapa("produtoId", pecaB, "quantidade", 1, "estado", "DEFEITO")))).andExpect(status().isCreated());

        devolucaoDaDonaDeM2 = devolverADona(maletaM2, pecaA, 1);
        UUID desfeita = devolverADona(maletaM2, pecaA, 1);
        postarDona(REMESSAS + "/" + maletaM2 + "/devolucoes/" + desfeita + "/estorno",
                mapa("id", UUID.randomUUID(), "motivo", "Lancei errado")).andExpect(status().isCreated());
    }

    /** O acerto das duas maletas; roda depois de quem quer olhar o retrato "antes". */
    void fecharOAcertoDoEstoque() throws Exception {
        acertoDoEstoque = iniciar(vendedorDoEstoque);
        bipar(acertoDoEstoque, pecaA, 4, "BOA");
        bipar(acertoDoEstoque, pecaB, 2, "DEFEITO");
        var decisoes = List.of(decisaoDeFalta(pecaA, "LOJA_ASSUME", "perda da loja"));
        var aprovados = List.of(defeitoAprovado(pecaB, 2));
        // O líquido certo vem da própria previa do servidor: a primeira tentativa erra de propósito e traz o valor.
        String corpo = fechar(acertoDoEstoque, pedidoDeFechamento(decisoes, aprovados, "999999.00")).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\"liquido\"\\s*:\\s*\"?(-?[0-9]+\\.[0-9]+)").matcher(corpo);
        if (!m.find()) {
            throw new IllegalStateException("A previa do acerto não trouxe o líquido: " + corpo);
        }
        fechar(acertoDoEstoque, pedidoDeFechamento(decisoes, aprovados, m.group(1))).andExpect(status().isCreated());
    }

    // --- passos que a massa da 7.8 não tem ---

    static Map<String, Object> linhaDeCompra(UUID peca, int quantidade) {
        return mapa("produtoId", peca, "quantidade", quantidade, "custoUnitario", "1.00");
    }

    UUID comprar(UUID fornecedor, List<Map<String, Object>> itens) throws Exception {
        UUID id = UUID.randomUUID();
        postarDona(COMPRAS, mapa("id", id, "fornecedorId", fornecedor, "dataCompra", "2026-11-01", "numero", "NF " + id, "itens", itens))
                .andExpect(status().isCreated());
        compras.add(id);
        return id;
    }

    /** Um inventário só da categoria da peça: conta {@code contado} e fecha (o ajuste sai do que o sistema esperava). */
    void inventarioDe(UUID peca, int contado) throws Exception {
        UUID categoria = umId("SELECT categoria_id FROM corona.produtos WHERE id = ?", peca);
        UUID inventario = UUID.randomUUID();
        postarDona("/api/dona/inventarios", mapa("id", inventario, "escopo", "CATEGORIA", "escopoId", categoria))
                .andExpect(status().isCreated());
        postarDona("/api/dona/inventarios/" + inventario + "/contagens", mapa("id", UUID.randomUUID(), "produtoId", peca, "contado", contado))
                .andExpect(status().isCreated());
        postarDona("/api/dona/inventarios/" + inventario + "/fechar", mapa("zerarNaoLidos", false)).andExpect(status().isOk());
    }

    /** Uma maleta enviada e cancelada em seguida (a baixa do estoque é estornada). */
    UUID maletaCancelada(UsuarioDeTeste vendedor, UUID peca, int quantidade) throws Exception {
        JsonNode r = corpoDe(postarDona(REMESSAS, mapa("id", UUID.randomUUID(), "vendedorId", vendedor.vendedorId(), "semanaId", semana(),
                "tipo", "NOVA")).andExpect(status().isCreated()));
        r = corpoDe(mvc.perform(put(REMESSAS + "/" + r.get("id").asString() + "/itens/" + peca).with(dona())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapa("quantidade", quantidade, "version", r.get("version").asInt()))))
                .andExpect(status().isOk()));
        UUID id = UUID.fromString(r.get("id").asString());
        postarDona(REMESSAS + "/" + id + "/enviar", null).andExpect(status().isCreated());
        postarDona(REMESSAS + "/" + id + "/cancelar", mapa("motivo", "Peça errada")).andExpect(status().isOk());
        return id;
    }

    UUID devolverADona(UUID maleta, UUID peca, int quantidade) throws Exception {
        UUID id = UUID.randomUUID();
        postarDona(REMESSAS + "/" + maleta + "/devolucoes", mapa("itens", List.of(mapa("id", id, "produtoId", peca, "quantidade", quantidade))))
                .andExpect(status().isCreated());
        return id;
    }

    int estoqueDaLoja(UUID peca) {
        return contar("SELECT estoque_atual FROM corona.produtos WHERE id = ?", peca);
    }
}
